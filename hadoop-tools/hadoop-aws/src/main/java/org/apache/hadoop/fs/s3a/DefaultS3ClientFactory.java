/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.classification.VisibleForTesting;
import org.apache.hadoop.fs.s3a.impl.AWSClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.awscore.util.AwsHostNameUtils;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.auth.spi.scheme.AuthScheme;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.metrics.publishers.cloudwatch.CloudWatchMetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3BaseClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.s3a.statistics.impl.AwsStatisticsCollector;
import org.apache.hadoop.fs.store.LogExactlyOnce;

import static org.apache.hadoop.fs.s3a.Constants.AWS_REGION;
import static org.apache.hadoop.fs.s3a.Constants.AWS_S3_CLIENT;
import static org.apache.hadoop.fs.s3a.Constants.AWS_S3_DEFAULT_REGION;
import static org.apache.hadoop.fs.s3a.Constants.CENTRAL_ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.FIPS_ENDPOINT;
import static org.apache.hadoop.fs.s3a.Constants.HTTP_SIGNER_CLASS_NAME;
import static org.apache.hadoop.fs.s3a.Constants.HTTP_SIGNER_ENABLED;
import static org.apache.hadoop.fs.s3a.Constants.HTTP_SIGNER_ENABLED_DEFAULT;
import static org.apache.hadoop.fs.s3a.Constants.DEFAULT_SECURE_CONNECTIONS;
import static org.apache.hadoop.fs.s3a.Constants.SECURE_CONNECTIONS;
import static org.apache.hadoop.fs.s3a.Constants.AWS_SERVICE_IDENTIFIER_S3;
import static org.apache.hadoop.fs.s3a.auth.SignerFactory.createHttpSigner;
import static org.apache.hadoop.fs.s3a.impl.AWSHeaders.REQUESTER_PAYS_HEADER;
import static org.apache.hadoop.fs.s3a.impl.InternalConstants.AUTH_SCHEME_AWS_SIGV_4;
import static org.apache.hadoop.util.Preconditions.checkArgument;


/**
 * The default {@link S3ClientFactory} implementation.
 * This calls the AWS SDK to configure and create an
 * {@code AmazonS3Client} that communicates with the S3 service.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class DefaultS3ClientFactory extends Configured
    implements S3ClientFactory {

  private static final String REQUESTER_PAYS_HEADER_VALUE = "requester";

  private static final String S3_SERVICE_NAME = "s3";

  /**
   * Subclasses refer to this.
   */
  protected static final Logger LOG =
      LoggerFactory.getLogger(DefaultS3ClientFactory.class);

  /**
   * A one-off warning of default region chains in use.
   */
  private static final LogExactlyOnce WARN_OF_DEFAULT_REGION_CHAIN =
      new LogExactlyOnce(LOG);

  /**
   * Warning message printed when the SDK Region chain is in use.
   */
  private static final String SDK_REGION_CHAIN_IN_USE =
      "S3A filesystem client is using"
          + " the SDK region resolution chain.";


  /** Exactly once log to inform about ignoring the AWS-SDK Warnings for CSE. */
  private static final LogExactlyOnce IGNORE_CSE_WARN = new LogExactlyOnce(LOG);

  /**
   * Error message when an endpoint is set with FIPS enabled: {@value}.
   */
  @VisibleForTesting
  public static final String ERROR_ENDPOINT_WITH_FIPS =
      "An endpoint cannot set when " + FIPS_ENDPOINT + " is true";

  @Override
  public S3Client createS3Client(
      final URI uri,
      final S3ClientCreationParameters parameters) throws IOException {

    Configuration conf = getConf();
    String bucket = uri.getHost();

    ApacheHttpClient.Builder httpClientBuilder = AWSClientConfig
        .createHttpClientBuilder(conf)
        .proxyConfiguration(AWSClientConfig.createProxyConfiguration(conf, bucket));
    return configureClientBuilder(S3Client.builder(), parameters, conf, bucket)
        .httpClientBuilder(httpClientBuilder)
        .build();
  }

  @Override
  public S3AsyncClient createS3AsyncClient(
      final URI uri,
      final S3ClientCreationParameters parameters) throws IOException {

    Configuration conf = getConf();
    String bucket = uri.getHost();

    String awsClient = conf.get(AWS_S3_CLIENT, null);

    SdkAsyncHttpClient.Builder httpClientBuilder;

    LOG.info("Overriding client executor with unbounded thread pool");
    System.out.println("Overriding client executor with unbounded thread pool");

    if (awsClient != null && awsClient.equals("CRT_HTTP")) {
      LOG.info("Using CRT HTTP client");
      System.out.println("Using CRT HTTP client");
      httpClientBuilder = AWSClientConfig
              .createAsyncCRTHTTPClientBuilder(conf);
    } else if (awsClient != null && awsClient.equals("CRT_S3")) {
      LOG.info("Using S3 CRT client");
      System.out.println("Using S3 CRT client");
      return createCRTAsyncClient(parameters, conf).build();
    } else {
      LOG.info("Using JAVA HTTP client");
      System.out.println("Using JAVA HTTP client");
      httpClientBuilder = AWSClientConfig
              .createAsyncHttpClientBuilder(conf)
              .proxyConfiguration(AWSClientConfig.createAsyncProxyConfiguration(conf, bucket));
    }

    MultipartConfiguration multipartConfiguration = MultipartConfiguration.builder()
        .minimumPartSizeInBytes(parameters.getMinimumPartSize())
        .thresholdInBytes(parameters.getMultiPartThreshold())
        .build();

    return configureClientBuilder(S3AsyncClient.builder(), parameters, conf, bucket)
        .httpClientBuilder(httpClientBuilder)
        .multipartConfiguration(multipartConfiguration)
        .multipartEnabled(parameters.isMultipartCopy())
            .asyncConfiguration(b -> b.advancedOption(SdkAdvancedAsyncClientOption
                            .FUTURE_COMPLETION_EXECUTOR,
                    parameters.getTransferManagerExecutor()
            ))
        .build();
  }

  @Override
  public S3TransferManager createS3TransferManager(final S3AsyncClient s3AsyncClient) {
    return S3TransferManager.builder()
        .s3Client(s3AsyncClient)
        .build();
  }

  private S3CrtAsyncClientBuilder createCRTAsyncClient(S3ClientCreationParameters parameters,
                                                       Configuration conf) {

    S3CrtAsyncClientBuilder s3CrtAsyncClientBuilder = S3AsyncClient.crtBuilder();

    String configuredRegion = parameters.getRegion();
    Region region;
    if(configuredRegion != null) {
      region = Region.of(configuredRegion);
      LOG.debug("Using region {}", region);
    }

    URI endpoint = getS3Endpoint(parameters.getEndpoint(), conf);
    if (endpoint != null) {
      s3CrtAsyncClientBuilder.endpointOverride(endpoint);
      LOG.debug("Using endpoint {}", endpoint);
    }

    return s3CrtAsyncClientBuilder
            .forcePathStyle(parameters.isPathStyleAccess())
            .credentialsProvider(parameters.getCredentialSet())
            .futureCompletionExecutor(parameters.getTransferManagerExecutor())
            .crossRegionAccessEnabled(true);

  }

  /**
   * Configure a sync or async S3 client builder.
   * This method handles all shared configuration, including
   * path style access, credentials and whether or not to use S3Express
   * CreateSession.
   * @param builder S3 client builder
   * @param parameters parameter object
   * @param conf configuration object
   * @param bucket bucket name
   * @return the builder object
   * @param <BuilderT> S3 client builder type
   * @param <ClientT> S3 client type
   */
  private <BuilderT extends S3BaseClientBuilder<BuilderT, ClientT>, ClientT> BuilderT configureClientBuilder(
      BuilderT builder, S3ClientCreationParameters parameters, Configuration conf, String bucket)
      throws IOException {

    configureEndpointAndRegion(builder, parameters, conf);

    S3Configuration serviceConfiguration = S3Configuration.builder()
        .pathStyleAccessEnabled(parameters.isPathStyleAccess())
        .checksumValidationEnabled(parameters.isChecksumValidationEnabled())
        .build();

    final ClientOverrideConfiguration.Builder override =
        createClientOverrideConfiguration(parameters, conf);

    S3BaseClientBuilder s3BaseClientBuilder = builder
        .overrideConfiguration(override.build())
        .credentialsProvider(parameters.getCredentialSet())
        .disableS3ExpressSessionAuth(!parameters.isExpressCreateSession())
        .serviceConfiguration(serviceConfiguration);

    if (conf.getBoolean(HTTP_SIGNER_ENABLED, HTTP_SIGNER_ENABLED_DEFAULT)) {
      // use an http signer through an AuthScheme
      final AuthScheme<AwsCredentialsIdentity> signer =
          createHttpSigner(conf, AUTH_SCHEME_AWS_SIGV_4, HTTP_SIGNER_CLASS_NAME);
      builder.putAuthScheme(signer);
    }
    return (BuilderT) s3BaseClientBuilder;
  }

  /**
   * Create an override configuration for an S3 client.
   * @param parameters parameter object
   * @param conf configuration object
   * @throws IOException any IOE raised, or translated exception
   * @throws RuntimeException some failures creating an http signer
   * @return the override configuration
   * @throws IOException any IOE raised, or translated exception
   */
  protected ClientOverrideConfiguration.Builder createClientOverrideConfiguration(
      S3ClientCreationParameters parameters, Configuration conf) throws IOException {
    final ClientOverrideConfiguration.Builder clientOverrideConfigBuilder =
        AWSClientConfig.createClientConfigBuilder(conf, AWS_SERVICE_IDENTIFIER_S3);

    // add any headers
    parameters.getHeaders().forEach((h, v) -> clientOverrideConfigBuilder.putHeader(h, v));

    if (parameters.isRequesterPays()) {
      // All calls must acknowledge requester will pay via header.
      clientOverrideConfigBuilder.putHeader(REQUESTER_PAYS_HEADER, REQUESTER_PAYS_HEADER_VALUE);
    }

    if (!StringUtils.isEmpty(parameters.getUserAgentSuffix())) {
      clientOverrideConfigBuilder.putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX,
          parameters.getUserAgentSuffix());
    }

    if (parameters.getExecutionInterceptors() != null) {
      for (ExecutionInterceptor interceptor : parameters.getExecutionInterceptors()) {
        clientOverrideConfigBuilder.addExecutionInterceptor(interceptor);
      }
    }

    if (parameters.getMetrics() != null) {
      clientOverrideConfigBuilder.addMetricPublisher(
          new AwsStatisticsCollector(parameters.getMetrics()));
    }

    final RetryPolicy.Builder retryPolicyBuilder = AWSClientConfig.createRetryPolicyBuilder(conf);
    clientOverrideConfigBuilder.retryPolicy(retryPolicyBuilder.build());

    System.out.println("Creating metrics publisher");

    SdkAsyncHttpClient asyncHttpClient = NettyNioAsyncHttpClient.create();

    MetricPublisher metricsPub = CloudWatchMetricPublisher.builder().cloudWatchClient(
            CloudWatchAsyncClient.builder().httpClient(asyncHttpClient).build()).build();


    clientOverrideConfigBuilder.addMetricPublisher(metricsPub);

    return clientOverrideConfigBuilder;
  }

  /**
   * This method configures the endpoint and region for a S3 client.
   * The order of configuration is:
   *
   * <ol>
   * <li>If region is configured via fs.s3a.endpoint.region, use it.</li>
   * <li>If endpoint is configured via via fs.s3a.endpoint, set it.
   *     If no region is configured, try to parse region from endpoint. </li>
   * <li> If no region is configured, and it could not be parsed from the endpoint,
   *     set the default region as US_EAST_2 and enable cross region access. </li>
   * <li> If configured region is empty, fallback to SDK resolution chain. </li>
   * </ol>
   *
   * @param builder S3 client builder.
   * @param parameters parameter object
   * @param conf  conf configuration object
   * @param <BuilderT> S3 client builder type
   * @param <ClientT> S3 client type
   * @throws IllegalArgumentException if endpoint is set when FIPS is enabled.
   */
  private <BuilderT extends S3BaseClientBuilder<BuilderT, ClientT>, ClientT> void configureEndpointAndRegion(
      BuilderT builder, S3ClientCreationParameters parameters, Configuration conf) {
    URI endpoint = getS3Endpoint(parameters.getEndpoint(), conf);

    String configuredRegion = parameters.getRegion();
    Region region = null;
    String origin = "";

    // If the region was configured, set it.
    if (configuredRegion != null && !configuredRegion.isEmpty()) {
      origin = AWS_REGION;
      region = Region.of(configuredRegion);
    }

    // FIPs? Log it, then reject any attempt to set an endpoint
    final boolean fipsEnabled = parameters.isFipsEnabled();
    if (fipsEnabled) {
      LOG.debug("Enabling FIPS mode");
    }
    // always setting it guarantees the value is non-null,
    // which tests expect.
    builder.fipsEnabled(fipsEnabled);

    if (endpoint != null) {
      checkArgument(!fipsEnabled,
          "%s : %s", ERROR_ENDPOINT_WITH_FIPS, endpoint);
      builder.endpointOverride(endpoint);
      // No region was configured, try to determine it from the endpoint.
      if (region == null) {
        region = getS3RegionFromEndpoint(parameters.getEndpoint());
        if (region != null) {
          origin = "endpoint";
        }
      }
      LOG.debug("Setting endpoint to {}", endpoint);
    }

    if (region != null) {
      builder.region(region);
    } else if (configuredRegion == null) {
      // no region is configured, and none could be determined from the endpoint.
      // Use US_EAST_2 as default.
      region = Region.of(AWS_S3_DEFAULT_REGION);
      builder.crossRegionAccessEnabled(true);
      builder.region(region);
      origin = "cross region access fallback";
    } else if (configuredRegion.isEmpty()) {
      // region configuration was set to empty string.
      // allow this if people really want it; it is OK to rely on this
      // when deployed in EC2.
      WARN_OF_DEFAULT_REGION_CHAIN.warn(SDK_REGION_CHAIN_IN_USE);
      LOG.debug(SDK_REGION_CHAIN_IN_USE);
      origin = "SDK region chain";
    }

    LOG.debug("Setting region to {} from {}", region, origin);
  }

  /**
   * Given a endpoint string, create the endpoint URI.
   *
   * @param endpoint possibly null endpoint.
   * @param conf config to build the URI from.
   * @return an endpoint uri
   */
  private static URI getS3Endpoint(String endpoint, final Configuration conf) {

    boolean secureConnections = conf.getBoolean(SECURE_CONNECTIONS, DEFAULT_SECURE_CONNECTIONS);

    String protocol = secureConnections ? "https" : "http";

    if (endpoint == null || endpoint.isEmpty()) {
      // don't set an endpoint if none is configured, instead let the SDK figure it out.
      return null;
    }

    if (!endpoint.contains("://")) {
      endpoint = String.format("%s://%s", protocol, endpoint);
    }

    try {
      return new URI(endpoint);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Parses the endpoint to get the region.
   * If endpoint is the central one, use US_EAST_1.
   *
   * @param endpoint the configure endpoint.
   * @return the S3 region, null if unable to resolve from endpoint.
   */
  private static Region getS3RegionFromEndpoint(String endpoint) {

    if(!endpoint.endsWith(CENTRAL_ENDPOINT)) {
      LOG.debug("Endpoint {} is not the default; parsing", endpoint);
      return AwsHostNameUtils.parseSigningRegion(endpoint, S3_SERVICE_NAME).orElse(null);
    }

    // endpoint is for US_EAST_1;
    return Region.US_EAST_1;
  }

}
