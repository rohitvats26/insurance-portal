package com.insurance.portal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3Client picks up credentials automatically from the EC2 instance role
 * (via the default credential provider chain) - no access keys are
 * hardcoded or stored on the instance. The instance role only needs
 * s3:PutObject on the target bucket (least privilege).
 */
@Configuration
public class S3ClientConfig {

    @Value("${aws.region}")
    private String region;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
