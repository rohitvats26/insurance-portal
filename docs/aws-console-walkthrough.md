# AWS Console Walkthrough — Insurance Document Portal (detailed, click-by-click)

Pick one region top-right (e.g. `ap-south-1`) and use it for every step below — mixing regions is the most common thing that breaks a walkthrough halfway through.

Order note: the brief lists VPC → Subnets → SGs → EC2/ASG → ALB → S3 → Lambda → RDS, but S3 and IAM are pulled earlier here because later steps need their ARNs. Everything else follows the requested order.

---

## Phase 1 — S3 bucket

1. Console search bar → type **S3** → open the S3 console.
2. Click **Create bucket**.
3. Bucket name: `insurance-portal-uploads-<yourname>` (must be globally unique — add random digits if it's taken).
4. Region: your chosen region.
5. Under **Block Public Access settings**, leave all four boxes checked (blocked) — do not change this.
6. Scroll to **Default encryption** → select **Amazon S3-managed keys (SSE-S3)**.
7. Click **Create bucket**.
8. Click into the new bucket → **Properties** tab → scroll to **Bucket ARN** → copy it somewhere (Notepad/sticky note) — you'll paste this into two IAM policies shortly.

---

## Phase 2 — VPC

1. Console search bar → type **VPC** → open the VPC console.
2. Left sidebar → **Your VPCs** → **Create VPC**.
3. Select **VPC only** (not "VPC and more" — we're building subnets manually so the layout matches the assignment exactly).
4. Name tag: `insurance-portal-vpc`.
5. IPv4 CIDR: `10.0.0.0/16`.
6. Leave everything else default → **Create VPC**.

---

## Phase 3 — Subnets

1. Left sidebar → **Subnets** → **Create subnet**.
2. VPC ID: select `insurance-portal-vpc`.
3. You can add all six subnets in one screen using **Add new subnet** six times. Fill in:

   | Subnet name | Availability Zone | IPv4 CIDR |
   |---|---|---|
   | public-a | (first AZ in the list) | 10.0.1.0/24 |
   | public-b | (second AZ in the list) | 10.0.2.0/24 |
   | private-app-a | same AZ as public-a | 10.0.11.0/24 |
   | private-app-b | same AZ as public-b | 10.0.12.0/24 |
   | private-db-a | same AZ as public-a | 10.0.21.0/24 |
   | private-db-b | same AZ as public-b | 10.0.22.0/24 |

4. Click **Create subnet**.
5. Now enable public IPs on the two public subnets only: select **public-a** → **Actions → Edit subnet settings** → check **Enable auto-assign public IPv4 address** → **Save**. Repeat for **public-b**.

---

## Phase 4 — Internet Gateway, route tables, and the S3 endpoint

1. Left sidebar → **Internet gateways** → **Create internet gateway** → name it `insurance-portal-igw` → **Create**.
2. On the new IGW's page → **Actions → Attach to VPC** → select `insurance-portal-vpc` → **Attach**.
3. Left sidebar → **Route tables** → **Create route table** → name `public-rt`, VPC `insurance-portal-vpc` → **Create**.
4. Open `public-rt` → **Routes** tab → **Edit routes** → **Add route** → Destination `0.0.0.0/0`, Target → Internet Gateway → select `insurance-portal-igw` → **Save changes**.
5. Same table → **Subnet associations** tab → **Edit subnet associations** → check `public-a` and `public-b` → **Save**.
6. Repeat step 3 to create `private-app-rt` and `private-db-rt` — leave both with only the default local route (don't add an IGW route to either; the S3 endpoint below handles S3 reachability instead).
7. Associate `private-app-rt` with `private-app-a`/`private-app-b`, and `private-db-rt` with `private-db-a`/`private-db-b` (same **Subnet associations** steps as above).
8. Left sidebar → **Endpoints** → **Create endpoint**.
9. Name: `s3-gateway-endpoint`. Service category: **AWS services**. Search for `s3` → select the entry with type **Gateway** (not Interface).
10. VPC: `insurance-portal-vpc`. Under **Route tables**, check `private-app-rt` (and `private-db-rt` if you want the DB tier to reach S3 too — not required, but harmless).
11. Click **Create endpoint**. This is what lets EC2 and Lambda in private subnets reach S3 with no NAT Gateway and no cost.
12. While you're here, enable two VPC-level settings you'll need later for the Secrets Manager endpoint in Phase 10: **Your VPCs** → select `insurance-portal-vpc` → **Actions → Edit VPC settings** → check both **Enable DNS resolution** and **Enable DNS hostnames** → **Save**. Skipping this now causes a "private DNS requires enableDnsSupport and enableDnsHostnames" error later when creating any Interface endpoint — easiest to just turn it on up front.

---

## Phase 5 — Security groups

1. Left sidebar → **Security groups** → **Create security group**.
2. Name `alb-sg`, VPC `insurance-portal-vpc`. Inbound rules → **Add rule** → Type: HTTP, Source: Anywhere-IPv4 (`0.0.0.0/0`). Leave outbound as default (all traffic). **Create security group**.
3. Repeat: name `ec2-sg`. Inbound rule → Type: Custom TCP, Port `8080`, Source: select **Custom** and start typing `alb-sg` to pick it from the dropdown (this scopes the rule to the security group, not an IP range). **Create security group**.
4. Repeat: name `lambda-sg`. No inbound rules needed — leave the inbound list empty. **Create security group**.
5. Repeat: name `rds-sg`. Inbound rule → Type: MySQL/Aurora (port 3306 auto-fills), Source: `lambda-sg`. **Create security group**.

   **Important**: don't add `ec2-sg` here. The EC2 app only talks to S3, never to RDS — only the Lambda function communicates with the database, per the assignment's explicit requirement in Part 5. Adding `ec2-sg` as a source (a mistake worth flagging since it's easy to do "just in case") would violate that requirement even though it doesn't cause any functional bug.

## Phase 5b — Network ACLs

Security groups (above) are stateful and attached to instances; NACLs are stateless and attached to subnets — both are explicitly required by the assignment. Because NACLs are stateless, every rule needs an explicit return-traffic rule in the opposite direction; ephemeral ports (1024–65535) cover the OS-assigned source ports used for responses.

1. Left sidebar → **Network ACLs** → **Create network ACL** → name `public-nacl`, VPC `insurance-portal-vpc` → **Create**.
2. **Inbound rules** → **Edit inbound rules**:
   - Rule 100: Allow, TCP, port 80, source `0.0.0.0/0` (HTTP from customers)
   - Rule 110: Allow, TCP, ports 1024-65535, source `0.0.0.0/0` (ephemeral return traffic)
3. **Outbound rules** → **Edit outbound rules**:
   - Rule 100: Allow, TCP, ports 1024-65535, destination `0.0.0.0/0` (responses to customers)
   - Rule 110: Allow, TCP, port 8080, destination `10.0.0.0/16` (forwarding to the app tier)
4. **Subnet associations** → associate `public-a` and `public-b`.
5. Repeat to create `private-app-nacl`:
   - Inbound: Allow TCP 8080 from `10.0.1.0/24` and `10.0.2.0/24` (from the ALB); Allow TCP 1024-65535 from `10.0.0.0/16` (return traffic from RDS and VPC endpoints)
   - Outbound: Allow TCP 3306 to `10.0.21.0/24` and `10.0.22.0/24` (to RDS); Allow TCP 443 to `0.0.0.0/0` (S3 and Secrets Manager endpoints); Allow TCP 1024-65535 to `10.0.1.0/24`/`10.0.2.0/24` (responses back to the ALB)
   - Associate with `private-app-a` and `private-app-b`
6. Repeat to create `private-db-nacl`:
   - Inbound: Allow TCP 3306 from `10.0.11.0/24` and `10.0.12.0/24` (from the app tier only)
   - Outbound: Allow TCP 1024-65535 to `10.0.11.0/24` and `10.0.12.0/24` (return traffic)
   - Associate with `private-db-a` and `private-db-b`

Each custom NACL denies everything not explicitly allowed (AWS adds this as the final rule automatically) — so any traffic pattern not listed above is blocked at the subnet boundary, independently of whatever the security groups allow.

---

## Phase 6 — IAM: dev team access, then service roles

Part 1 of the assignment asks for two different things here — an IAM group for actual human developers to log in with, and IAM roles for AWS services (EC2, Lambda) to assume. These are separate IAM concepts; both are required.

### Dev team IAM group (human access)

1. Console search bar → type **IAM** → open the IAM console.
2. Left sidebar → **User groups** → **Create group** → name `insurance-portal-devs`.
3. **Attach permissions policies** → search for and attach AWS-managed policies scoped to just the services this assignment uses: `AmazonEC2ReadOnlyAccess` or a tighter custom policy, `AmazonS3ReadOnlyAccess`, `AWSLambda_ReadOnlyAccess`, `ElasticLoadBalancingReadOnlyAccess`, `AmazonRDSReadOnlyAccess` — read-only is a reasonable default for a small workshop team; if the team needs to actually create/modify resources, use the non-read-only equivalents instead, but still avoid `AdministratorAccess`.
4. **Create group**.
5. **Users** → **Add users** → create one user per team member (or add existing users) → on the permissions step, **Add user to group** → select `insurance-portal-devs` → finish the wizard.

This satisfies "create IAM users/groups for the development team" with least privilege, separately from the service roles below.

### Service roles (what EC2 and Lambda themselves assume)

1. Left sidebar → **Roles** → **Create role**.
2. Trusted entity type: **AWS service** → Use case: **EC2** → **Next**.
3. Skip attaching a managed policy for now → **Next** → Role name: `ec2-upload-role` → **Create role**.
5. Open the new role → **Add permissions → Create inline policy** → **JSON** tab → paste:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": "s3:PutObject",
       "Resource": "arn:aws:s3:::insurance-portal-uploads-<yourname>/*"
     }]
   }
   ```
   (use the bucket ARN you copied in Phase 1, with `/*` appended) → **Next** → name it `ec2-s3-put` → **Create policy**.
6. Back at **Roles → Create role** → Trusted entity: **AWS service** → Use case: **Lambda** → **Next**.
7. Attach these AWS managed policies: `AWSLambdaBasicExecutionRole` and `AWSLambdaVPCAccessExecutionRole` → **Next** → name it `lambda-processor-role` → **Create role**.
8. Open `lambda-processor-role` → **Add permissions → Create inline policy** → **JSON** → paste:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": "s3:GetObject",
       "Resource": "arn:aws:s3:::insurance-portal-uploads-<yourname>/*"
     }]
   }
   ```
   → name it `lambda-s3-get` → **Create policy**.
9. Leave a second inline policy for Secrets Manager until Phase 7, once the secret ARN exists.

---

## Phase 7 — RDS

1. Console search bar → **RDS** → open the RDS console.
2. Left sidebar → **Subnet groups** → **Create DB subnet group**.
3. Name: `insurance-db-subnet-group`. VPC: `insurance-portal-vpc`. Add subnets: check `private-db-a` and `private-db-b` → **Create**.
4. Left sidebar → **Databases** → **Create database**.
5. Choose **Standard create**. Engine: **MySQL**.
6. Templates: **Free tier**.
7. DB instance identifier: `insurance-portal-db`.
8. Under **Credentials management**, select **Manage master credentials in AWS Secrets Manager** — this auto-creates the secret you need for the Lambda bonus, so you don't have to build it by hand.
9. Instance configuration: leave the Free-tier-selected `db.t3.micro`.
10. Storage: leave defaults.
11. Under **Connectivity**: VPC = `insurance-portal-vpc`; DB subnet group = `insurance-db-subnet-group`; **Public access: No**; VPC security group: choose **Existing**, select `rds-sg` (remove the default one if it's auto-added).
12. Additional configuration → Initial database name: `insurance_portal`.
13. Click **Create database** (takes several minutes to become "Available").
14. Once available, open the database → **Configuration** tab → find and copy the **Secrets Manager ARN** shown near the master credentials — you'll need it in the next two steps. Also copy the **Endpoint** value from the **Connectivity & security** tab now — you'll need it too.
15. Go back to IAM → `lambda-processor-role` → **Add permissions → Create inline policy** → **JSON**:
    ```json
    {
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Action": "secretsmanager:GetSecretValue",
        "Resource": "<the secret ARN from step 14>"
      }]
    }
    ```
    → name it `lambda-secrets-get` → **Create policy**.

**Important**: this auto-generated secret only contains `username` and `password` — it does **not** contain `host`, `port`, or `dbname`, since RDS treats those as connection details rather than credentials. Lambda will need `DB_HOST`, `DB_PORT`, and `DB_NAME` as separate plain environment variables alongside `DB_SECRET_ARN` — this is covered in Phase 10, but worth knowing now so the Lambda code isn't written expecting those fields inside the secret's JSON.

---

## Phase 8 — Load the schema (temporary bastion)

1. EC2 console → **Launch instance**. Name: `temp-bastion`. AMI: Amazon Linux 2023. Instance type: `t2.micro`.
2. Key pair: create/select one you can SSH with.
3. Network settings → **Edit** → VPC: `insurance-portal-vpc`; Subnet: `public-a`; Auto-assign public IP: **Enable**.
4. Security group: create a new one, `bastion-sg`, inbound rule SSH (22) from **My IP**.
5. **Launch instance**.
6. Go to `rds-sg` → **Edit inbound rules** → **Add rule** → MySQL/Aurora (3306), Source: `bastion-sg` → **Save rules**.
7. SSH into the bastion using its public IP and your key pair.
8. Install a MySQL client: `sudo dnf install -y mariadb105`.
9. Connect: `mysql -h <rds-endpoint> -u admin -p insurance_portal` (get the endpoint from the RDS console's **Connectivity** tab; get the password from the Secrets Manager console entry for this DB).
10. Paste in the contents of `db/schema.sql` from the project, or run `source schema.sql` if you've copied the file to the bastion.
11. Once confirmed, **terminate the bastion instance** and remove the `bastion-sg` rule from `rds-sg` — neither is part of the real architecture.
---

## Phase 9 — EC2 launch template, target group, ALB, Auto Scaling Group

1. EC2 console → **Launch Templates** → **Create launch template**.
2. Name: `upload-app-template`. AMI: Amazon Linux 2023. Instance type: `t2.micro` or `t3.micro`.

   **Gotcha**: under "Application and OS Images", you have to actually click the Amazon Linux quick-start tile itself (not just glance at the section) for the AMI ID to register — it's easy to scroll past it without selecting anything and get a "No AMI specified for the current launch template" error later. If that happens, reopen the template, click the tile, and confirm an AMI ID appears underneath it before continuing. If the tile click doesn't register, search for "Amazon Linux 2023" in the search box above the tiles instead and pick it from the results list.
3. Key pair: same or new one (optional if you don't plan to SSH in directly).
4. Network settings → check **Don't include in launch template** for subnet (the ASG assigns subnets) but do set security group: `ec2-sg`.
5. Advanced details → **IAM instance profile**: `ec2-upload-role`.
6. Advanced details → **User data**, paste the script below.

   ```bash
   #!/bin/bash
   set -e

   # Amazon Corretto 17 — Amazon Linux 2023's supported Java 17 build
   dnf install -y java-17-amazon-corretto

   mkdir -p /opt/app

   # Pull the pre-built jar from a SEPARATE artifacts bucket/prefix — not the
   # uploads bucket. Keeping app deployment artifacts apart from customer
   # documents means this role never needs read access to uploaded files.
   aws s3 cp s3://insurance-portal-artifacts-<yourname>/upload-app.jar /opt/app/upload-app.jar

   cat > /etc/systemd/system/upload-app.service << 'EOF'
   [Unit]
   Description=Insurance Portal Upload App
   After=network.target

   [Service]
   User=ec2-user
   Environment=S3_BUCKET_NAME=insurance-portal-uploads-<yourname>
   Environment=AWS_REGION=<your-region>
   ExecStart=/usr/bin/java -jar /opt/app/upload-app.jar
   Restart=always

   [Install]
   WantedBy=multi-user.target
   EOF

   systemctl daemon-reload
   systemctl enable upload-app
   systemctl start upload-app
   ```

   AWS CLI is preinstalled on the Amazon Linux 2023 AMI, so no extra setup is needed for the `aws s3 cp` line. This script runs automatically as root the first time the instance boots (that's what "user data" means to EC2) — you won't see it execute, but you can verify it worked by checking `systemctl status upload-app` after connecting to an instance.

   **Important IAM addition**: this script needs `s3:GetObject` on the artifacts bucket/key — a *different* permission from the `s3:PutObject` on the uploads bucket that `ec2-upload-role` already has. Go back to `ec2-upload-role` and add a second inline policy:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": "s3:GetObject",
       "Resource": "arn:aws:s3:::insurance-portal-artifacts-<yourname>/upload-app.jar"
     }]
   }
   ```
   Scoping this to the exact object key (not `/*`) means the role still can't read anything else in that bucket, let alone the customer uploads bucket — least privilege stays intact even with this addition. Before this will work, manually upload `upload-app.jar` (built earlier with `mvn clean package`) to `s3://insurance-portal-artifacts-<yourname>/upload-app.jar` via the S3 console.
7. **Create launch template**.
8. EC2 console → **Target Groups** → **Create target group**. Type: **Instances**. Name: `upload-app-tg`. Protocol: HTTP, Port: `8080`. VPC: `insurance-portal-vpc`.
9. Health checks → Path: `/actuator/health`. Leave the rest default → **Next** → skip registering targets manually (the ASG does this) → **Create target group**.
10. EC2 console → **Load Balancers** → **Create load balancer** → **Application Load Balancer**.
11. Name: `insurance-portal-alb`. Scheme: **Internet-facing**.
12. VPC: `insurance-portal-vpc`. Mappings: check both AZs, selecting `public-a` and `public-b`.
13. Security groups: remove default, add `alb-sg`.
14. Listeners: HTTP:80 → default action → **Forward to** → `upload-app-tg`.
15. **Create load balancer**.
16. EC2 console → **Auto Scaling Groups** → **Create Auto Scaling group**.
17. Name: `upload-app-asg`. Launch template: `upload-app-template`.
18. Network: VPC `insurance-portal-vpc`, subnets: `private-app-a` and `private-app-b`.
19. Attach to existing load balancer target groups → select `upload-app-tg`.
20. Group size: Desired `2`, Minimum `1`, Maximum `4`.
21. Scaling policies → **Target tracking scaling policy** → Metric type: Average CPU utilization, Target value: `50`.
22. **Create Auto Scaling group**.

---

## Phase 10 — Lambda

1. Lambda console → **Create function**.
2. **Author from scratch**. Name: `s3-upload-processor`. Runtime: **Java 17**.
3. Execution role: **Use an existing role** → `lambda-processor-role`.
4. **Create function**.
5. **Code** tab → **Upload from** → **.zip or .jar file** → upload `lambda-processor.jar` from the project's `target/` folder. (If it's over 50 MB — unlikely for this project, but check with `ls -lh` — upload to S3 first and use **Upload from → Amazon S3 location** instead.)
6. **Runtime settings → Edit** → Handler: `com.insurance.lambda.S3UploadProcessor::handleRequest`.
7. **Configuration** tab → **General configuration → Edit** → Timeout: `15` sec, Memory: `512` MB → **Save**.
8. **Configuration** tab → **VPC → Edit** → VPC: `insurance-portal-vpc`, Subnets: `private-app-a` and `private-app-b`, Security group: `lambda-sg` → **Save**.
9. **Configuration** tab → **Environment variables → Edit** → add all of the following (not just the secret ARN — the auto-generated secret doesn't carry connection details, per the note in Phase 7):
   - `DB_SECRET_ARN` = the secret ARN from Phase 7
   - `DB_HOST` = the RDS endpoint you copied in Phase 7
   - `DB_NAME` = `insurance_portal`
   - `AWS_REGION` = your region

   → **Save**.
10. **Add the Secrets Manager Interface Endpoint** — without this, the Lambda hangs and times out at invocation trying to reach Secrets Manager, since it has no NAT Gateway and Secrets Manager has no free Gateway endpoint option (only S3 does):
    - Create a security group `secretsmanager-endpoint-sg`: inbound HTTPS (443) from `lambda-sg`.
    - VPC console → **Endpoints** → **Create endpoint** → name `secretsmanager-endpoint` → search `secretsmanager` → select the **Interface** type result.
    - VPC: `insurance-portal-vpc`. Subnets: `private-app-a` and `private-app-b`. Security group: `secretsmanager-endpoint-sg`.
    - **Create endpoint**, wait for it to show **Available** before testing.
11. **Double-check `rds-sg` allows `lambda-sg`** on port 3306 — this exact rule is the single most common point of failure in this whole build, especially if you've been adding/removing other temporary security groups (like a bastion) along the way. Worth a direct visual check in `rds-sg` → **Inbound rules** before moving on.
12. **Configuration** tab → **Triggers → Add trigger** → Source: **S3** → Bucket: your uploads bucket → Event type: **All object create events** (or just PUT) → check the acknowledgement box → **Add**.

---

## Phase 11 — CloudWatch dashboard and alarms

1. SNS console → **Topics** → **Create topic** → Standard, name `insurance-portal-alerts` → **Create topic**. Then **Create subscription** → Protocol: Email, Endpoint: your address → **Create subscription** → confirm via the email link. Alarms won't notify you until this is confirmed.
2. CloudWatch console → **Alarms** → **Create alarm** → Lambda → By Function Name → `s3-upload-processor` → **Errors**. Statistic: Sum, Period: 5 min, Condition: Greater than 0, Notification: `insurance-portal-alerts`. Name: `lambda-errors-alarm`.
3. **Create alarm** → RDS → Per-Database Metrics → `insurance-portal-db` → **CPUUtilization**. Condition: Greater than 80 for 2/2 datapoints. Name: `rds-high-cpu-alarm`. Repeat for **FreeStorageSpace**, condition Less than ~2GB, name `rds-low-storage-alarm`.
4. **Create alarm** → ApplicationELB → Per AppELB Metrics → your ALB → **UnHealthyHostCount**. Condition: Greater than 0 for 2 consecutive periods. Name: `alb-unhealthy-targets-alarm` — this is the highest-value alarm here, since it flags the moment the app stops serving traffic.
5. CloudWatch console → **Dashboards** → **Create dashboard** → `insurance-portal-dashboard`. Add widgets: a line graph for Lambda Invocations/Errors/Duration; a line graph for ALB RequestCount/TargetResponseTime/HTTPCode_Target_5XX_Count; a line graph for RDS CPUUtilization/DatabaseConnections/FreeStorageSpace; a number widget for ASG GroupInServiceInstances. **Save dashboard**.
6. Verify it actually works before recording: upload a test file, then temporarily remove the `rds-sg` → `lambda-sg` rule and upload again to force a Lambda error. Confirm the alarm fires and the email arrives, then restore the rule. Worth showing briefly in the walkthrough recording as proof the monitoring functions, not just that it exists.

---
