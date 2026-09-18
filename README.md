# Insurance self-service portal - source code

Two components:

- **upload-app/** - Spring Boot app hosted on EC2 (behind the ALB). Serves a
  one-page upload form and stores files in S3.
- **lambda-processor/** - Java Lambda triggered by S3 `ObjectCreated` events.
  Reads the object's Content-Type, writes a row to RDS, logs to CloudWatch.

## 1. Prerequisites

- Java 17, Maven 3.9+
- An AWS account with the VPC/subnets/S3/RDS/Lambda already provisioned
  (see the architecture diagram and console walkthrough)
- An S3 bucket for uploads
- An RDS MySQL instance, with `db/schema.sql` applied

## 2. Build

```bash
# Spring Boot app
cd upload-app
mvn clean package
# -> target/upload-app.jar

# Lambda function (produces a shaded/fat jar for deployment)
cd ../lambda-processor
mvn clean package
# -> target/lambda-processor.jar
```

## 3. Deploy the Spring Boot app to EC2

1. Copy `upload-app.jar` to the EC2 instance (or bake it into an AMI used by
   the Auto Scaling Group's launch template).
2. Set environment variables (or pass as `-D` system properties):
   - `S3_BUCKET_NAME` - the uploads bucket name
   - `AWS_REGION` - e.g. `ap-south-1`
   - `SERVER_PORT` - defaults to 8080 (put the ALB target group on this port)
3. Run as a service, e.g. a systemd unit:

   ```ini
   [Unit]
   Description=Insurance Portal Upload App
   After=network.target

   [Service]
   User=ec2-user
   Environment=S3_BUCKET_NAME=insurance-portal-uploads
   Environment=AWS_REGION=ap-south-1
   ExecStart=/usr/bin/java -jar /opt/app/upload-app.jar
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```

4. Attach an IAM instance role to the EC2 instance (via the launch template)
   with only `s3:PutObject` on the uploads bucket ARN. No access keys are
   stored on the instance - the AWS SDK picks up the role automatically.

## 4. Deploy the Lambda function

1. Upload `lambda-processor.jar` as the Lambda's deployment package.
2. Handler: `com.insurance.lambda.S3UploadProcessor::handleRequest`
3. Runtime: Java 17
4. Attach the Lambda to the VPC's **private application subnets** (both AZs)
   so it can reach RDS privately, and assign it a security group that RDS's
   security group allows on the DB port.
5. Add an S3 trigger: `PUT` events on the uploads bucket.
6. Environment variables (pick ONE of the two credential paths):
   - **Recommended (bonus): Secrets Manager** - set `DB_SECRET_ARN` to the
     ARN of a secret shaped like:
     ```json
     { "host": "...", "port": 3306, "dbname": "insurance_portal", "username": "...", "password": "..." }
     ```
     Grant the Lambda execution role `secretsmanager:GetSecretValue` on that
     one ARN only.
   - **Simple path:** set `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
     `DB_PASSWORD` directly as environment variables (encrypt with a KMS key
     for anything beyond a demo).
7. IAM role also needs: `s3:GetObject` on the uploads bucket, and CloudWatch
   Logs write permissions (`AWSLambdaBasicExecutionRole` covers this) plus
   `AWSLambdaVPCAccessExecutionRole` for running inside the VPC.

## 5. Database

Apply `db/schema.sql` once, from an instance inside the VPC (RDS has no
public endpoint). The Lambda inserts one row per successful upload.

## 6. Security notes

- EC2 role: `s3:PutObject` on the specific bucket only - nothing else.
- Lambda role: `s3:GetObject` on the bucket, RDS network access via SG,
  optionally `secretsmanager:GetSecretValue` on one secret, plus basic +
  VPC CloudWatch/ENI execution policies.
- RDS security group only allows inbound on the DB port from the Lambda
  and EC2 security groups - no public route, no 0.0.0.0/0 rule.
- All of this maps to the security groups and IAM policies referenced in
  the architecture diagram.
