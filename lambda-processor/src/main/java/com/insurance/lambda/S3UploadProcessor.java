package com.insurance.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Triggered by an S3 "ObjectCreated" event notification on the uploads bucket.
 *
 * For each uploaded object:
 *   1. Reads the object's metadata (Content-Type) via S3 HeadObject.
 *   2. Inserts (file name, content type, upload timestamp) into RDS.
 *   3. Logs progress to CloudWatch (stdout/stderr are captured automatically).
 *
 * Runs inside the VPC's private application subnets so it can reach RDS
 * over the private network - the Lambda's security group is the only one
 * allowed to talk to the RDS security group on the DB port.
 */
public class S3UploadProcessor implements RequestHandler<S3Event, String> {

    private final S3Client s3Client = S3Client.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "ap-south-1")))
            .build();

    @Override
    public String handleRequest(S3Event event, Context context) {
        for (S3EventNotification.S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();
            String key = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);

            context.getLogger().log("Processing upload: bucket=" + bucket + " key=" + key);

            try {
                HeadObjectResponse metadata = s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build());

                String contentType = metadata.contentType() != null ? metadata.contentType() : "unknown";
                Instant uploadTimestamp = Instant.now();

                insertMetadata(key, contentType, uploadTimestamp, context);

                context.getLogger().log("Stored metadata for " + key
                        + " (contentType=" + contentType + ") in RDS.");

            } catch (Exception e) {
                // Log and continue so one bad record doesn't fail the whole batch.
                context.getLogger().log("ERROR processing " + key + ": " + e.getMessage());
            }
        }
        return "Processed " + event.getRecords().size() + " record(s).";
    }

    private void insertMetadata(String fileName, String contentType, Instant uploadTimestamp, Context context)
            throws Exception {

        String secretArn = System.getenv("DB_SECRET_ARN");

        String jdbcUrl;
        String dbUser;
        String dbPassword;

        if (secretArn != null && !secretArn.isBlank()) {
            // Bonus path: credentials pulled fresh from Secrets Manager on every invocation.
            SecretsManagerHelper.DbCredentials creds =
                    new SecretsManagerHelper().fetchDbCredentials(secretArn);
            jdbcUrl = creds.jdbcUrl();
            dbUser = creds.username();
            dbPassword = creds.password();
        } else {
            // Fallback path: plain environment variables (simplest to demo without Secrets Manager set up).
            jdbcUrl = "jdbc:mysql://" + System.getenv("DB_HOST") + ":"
                    + System.getenv().getOrDefault("DB_PORT", "3306") + "/"
                    + System.getenv("DB_NAME") + "?useSSL=true&serverTimezone=UTC";
            dbUser = System.getenv("DB_USER");
            dbPassword = System.getenv("DB_PASSWORD");
        }

        String sql = "INSERT INTO uploads (file_name, content_type, upload_timestamp) VALUES (?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, fileName);
            stmt.setString(2, contentType);
            stmt.setTimestamp(3, Timestamp.from(uploadTimestamp));
            stmt.executeUpdate();
        }
    }
}
