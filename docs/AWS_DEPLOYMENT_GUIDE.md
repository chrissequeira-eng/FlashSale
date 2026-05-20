# Flash Sale System — Complete AWS Deployment Guide

> **Read this top to bottom.** Each section builds on the previous one.
> Commands marked with `$` run on your local machine. Commands marked `[EC2]` run on your EC2 instance.

---

## TABLE OF CONTENTS

1. [Run Locally with Docker Compose](#1-run-locally)
2. [Deploy to EC2 (Both Services)](#2-deploy-to-ec2)
3. [Create an AMI (Golden Image)](#3-create-ami)
4. [Create a Launch Template](#4-launch-template)
5. [Create the Application Load Balancer](#5-create-alb)
6. [Create the Auto Scaling Group](#6-create-asg)
7. [Connect ALB to ASG](#7-connect-alb-asg)
8. [Configure CloudWatch Alarms](#8-cloudwatch-alarms)
9. [Run Load Tests](#9-load-testing)
10. [Understanding Auto Scaling Concepts](#10-concepts)

---

## 1. Run Locally

**Prerequisites:** Docker Desktop installed and running.

```bash
# Clone or create the project, then from the root folder:
$ docker-compose up --build

# You should see:
#   mysql      → Started
#   product-service → Started on :8081
#   order-service   → Started on :8080
```

**Test it works:**

```bash
# List products
$ curl http://localhost:8081/products

# Place an order
$ curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "quantity": 1}'

# Check health
$ curl http://localhost:8080/actuator/health
$ curl http://localhost:8081/actuator/health
```

**Expected response:**
```json
{
  "status": "SUCCESS",
  "message": "Order placed successfully",
  "productId": 1,
  "quantity": 1,
  "instanceId": "docker-local"
}
```

---

## 2. Deploy to EC2

### 2a. Launch Two EC2 Instances

Go to **AWS Console → EC2 → Launch Instance**.

**Settings for BOTH instances:**
- AMI: `Amazon Linux 2023` (free tier eligible)
- Instance type: `t2.micro` (free tier — 1 vCPU, 1GB RAM)
- Key pair: Create or select an existing key pair (save the `.pem` file!)
- Security Group: Create new with these rules:

| Type  | Protocol | Port | Source    | Purpose                          |
|-------|----------|------|-----------|----------------------------------|
| SSH   | TCP      | 22   | Your IP   | SSH access                       |
| HTTP  | TCP      | 8080 | Anywhere  | Order Service                    |
| HTTP  | TCP      | 8081 | Anywhere  | Product Service                  |
| HTTP  | TCP      | 3306 | Anywhere  | MySQL (for product-service only) |

> **Cost note:** t2.micro is free for 750 hours/month in your first year.

Launch **two separate instances:**
- `flashsale-product` — for MySQL + Product Service
- `flashsale-order` — for Order Service (this becomes your AMI base)

---

### 2b. Install Docker on Both EC2 Instances

SSH into each instance:

```bash
$ ssh -i your-key.pem ec2-user@<EC2_PUBLIC_IP>
```

Then run:

```bash
[EC2] sudo yum update -y
[EC2] sudo yum install docker -y
[EC2] sudo service docker start
[EC2] sudo usermod -aG docker ec2-user
[EC2] newgrp docker  # Apply group change without logout

# Verify
[EC2] docker --version
```

---

### 2c. Set Up Product Service Instance

SSH into `flashsale-product`:

```bash
# Create a directory for configs
[EC2] mkdir ~/flashsale && cd ~/flashsale

# Start MySQL in Docker
[EC2] docker run -d \
  --name mysql \
  --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD=password \
  -e MYSQL_DATABASE=flashsale \
  -p 3306:3306 \
  mysql:8.0

# Wait ~30 seconds for MySQL to initialize, then verify:
[EC2] docker logs mysql | tail -20

# Build and run Product Service
# Option A: Pull from Docker Hub (if you pushed it there)
# Option B: Build directly on EC2

# OPTION B - Build on EC2:
[EC2] sudo yum install git maven java-21-amazon-corretto -y

# Copy your product-service folder to EC2 (run this on YOUR machine):
$ scp -i your-key.pem -r ./product-service ec2-user@<PRODUCT_EC2_IP>:~/flashsale/

# Back on EC2 - build and run:
[EC2] cd ~/flashsale/product-service
[EC2] mvn clean package -DskipTests
[EC2] java -jar target/product-service-1.0.0.jar \
  --spring.datasource.url=jdbc:mysql://localhost:3306/flashsale?useSSL=false\&allowPublicKeyRetrieval=true\&serverTimezone=UTC \
  --spring.datasource.username=root \
  --spring.datasource.password=password &

# Test it
[EC2] curl http://localhost:8081/products
```

> **Note the PRIVATE IP of this instance** — you'll need it for Order Service.
> Find it in AWS Console under EC2 → Instances → Private IPv4 address.

---

### 2d. Set Up Order Service Instance

SSH into `flashsale-order`:

```bash
# Copy order-service to EC2:
$ scp -i your-key.pem -r ./order-service ec2-user@<ORDER_EC2_IP>:~/flashsale/

[EC2] sudo yum install java-21-amazon-corretto maven -y
[EC2] cd ~/flashsale/order-service
[EC2] mvn clean package -DskipTests

# Start with Product Service URL pointing to the product instance
# Replace <PRODUCT_PRIVATE_IP> with the actual private IP
[EC2] PRODUCT_SERVICE_URL=http://<PRODUCT_PRIVATE_IP>:8081 \
      INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id) \
      java -jar target/order-service-1.0.0.jar &

# Test it
[EC2] curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "quantity": 1}'
```

---

## 3. Create an AMI (Golden Image)

An AMI (Amazon Machine Image) is a **snapshot of your EC2 instance**.
The Auto Scaling Group uses this to launch new identical instances.

1. Go to **EC2 → Instances**
2. Select your `flashsale-order` instance
3. Click **Actions → Image and templates → Create image**
4. Settings:
   - Image name: `flashsale-order-service-v1`
   - No reboot: ✅ (keeps instance running during snapshot)
5. Click **Create image**
6. Wait 3-5 minutes for the AMI to become `available`

> **Why create an AMI?**
> Without an AMI, each new Auto Scaling instance would start blank and need manual setup.
> With an AMI, new instances start pre-configured with your app installed.

---

## 4. Create a Launch Template

A Launch Template tells the ASG: "when you need to launch a new instance, use THESE settings."

1. Go to **EC2 → Launch Templates → Create launch template**
2. Settings:

   **Launch template name:** `flashsale-order-lt`

   **AMI:** Select `flashsale-order-service-v1` (your AMI from step 3)

   **Instance type:** `t2.micro`

   **Key pair:** Select your existing key pair

   **Security Group:** Same security group you created earlier

   **Advanced details → User data:**
   Paste this script (it runs when each new instance starts):

```bash
#!/bin/bash
# This script runs ONCE when a new EC2 instance starts from this template.

# Get the instance's own ID from EC2 metadata service
INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)

# Get Product Service private IP - REPLACE THIS with your actual IP
PRODUCT_IP="<PRODUCT_PRIVATE_IP>"

# Start the Order Service
# It automatically sets instanceId so responses show WHICH instance handled the request
cd /home/ec2-user/flashsale/order-service
nohup java -jar target/order-service-1.0.0.jar \
  --product.service.url=http://${PRODUCT_IP}:8081 \
  --instance.id=${INSTANCE_ID} \
  > /home/ec2-user/app.log 2>&1 &

echo "Order Service started with instanceId: $INSTANCE_ID"
```

3. Click **Create launch template**

---

## 5. Create the Application Load Balancer

The ALB receives all incoming traffic and distributes it across Order Service instances.

### 5a. Create a Target Group First

**EC2 → Target Groups → Create target group**

- Target type: `Instances`
- Target group name: `flashsale-order-tg`
- Protocol: `HTTP`
- Port: `8080`
- VPC: Your default VPC
- Health check:
  - Protocol: `HTTP`
  - Path: `/actuator/health`   ← Spring Actuator health endpoint
  - Healthy threshold: `2`     ← 2 consecutive successes = healthy
  - Unhealthy threshold: `2`   ← 2 consecutive failures = unhealthy
  - Timeout: `5 seconds`
  - Interval: `30 seconds`

Click **Next**, then **Create target group** (don't add instances yet — ASG will do this).

---

### 5b. Create the ALB

**EC2 → Load Balancers → Create Load Balancer → Application Load Balancer**

- Name: `flashsale-alb`
- Scheme: `Internet-facing` (accepts traffic from internet)
- IP address type: `IPv4`
- VPC: Your default VPC
- Availability Zones: Select **at least 2** AZs (required for ALB)
- Security Group: Same security group (needs port 80 open)
- Listeners:
  - Protocol: `HTTP`, Port: `80`
  - Default action: Forward to `flashsale-order-tg`

Click **Create load balancer**.

> Note the ALB's **DNS name** — this is your entry point for load tests.
> It looks like: `flashsale-alb-123456.us-east-1.elb.amazonaws.com`

---

## 6. Create the Auto Scaling Group

The ASG monitors CPU and automatically adds/removes Order Service instances.

**EC2 → Auto Scaling Groups → Create Auto Scaling group**

**Step 1 - Name and launch template:**
- Name: `flashsale-order-asg`
- Launch template: `flashsale-order-lt`

**Step 2 - Network:**
- VPC: Default VPC
- Availability Zones: Select **same AZs** as your ALB

**Step 3 - Load balancing:**
- Attach to existing load balancer
- Target groups: `flashsale-order-tg`
- Health checks: Enable ELB health checks ✅

**Step 4 - Group size:**
- Desired capacity: `1`
- Minimum capacity: `1`
- Maximum capacity: `2`

**Step 5 - Scaling policies:**
Choose **Target tracking scaling policy**:
- Policy name: `cpu-scaling-policy`
- Metric type: `Average CPU Utilization`
- Target value: `60`   ← Scale out when CPU > 60%, scale in when < 60%

> Under the hood this creates two CloudWatch alarms automatically.

**Step 6 - Notifications:** Skip for now.

Click **Create Auto Scaling group**.

---

## 7. Connect ALB to ASG

The ASG is already connected (you chose the target group in Step 6).

**Verify the connection:**
1. EC2 → Target Groups → `flashsale-order-tg` → Targets tab
2. You should see your instance in the list with status `healthy`

**If the health check shows unhealthy:**
- SSH into the instance and check: `curl http://localhost:8080/actuator/health`
- Check app logs: `tail -f ~/app.log`
- Common issue: app hasn't finished starting (takes ~30s for Spring Boot)

---

## 8. Configure CloudWatch Alarms

The target tracking policy creates alarms automatically, but let's create
explicit alarms so we can observe them clearly.

**CloudWatch → Alarms → Create alarm**

### Scale-Out Alarm (CPU High)
- Metric: EC2 → By Auto Scaling Group → CPUUtilization → `flashsale-order-asg`
- Statistic: Average
- Period: 1 minute
- Threshold: Greater than 60 for 2 consecutive periods
- Action: Already handled by ASG policy

### Scale-In Alarm (CPU Low)
- Same metric
- Threshold: Less than 20 for 5 consecutive periods
- Action: Already handled by ASG policy

**Why 5 periods for scale-in but 2 for scale-out?**
See section 10 (Concepts) for the explanation.

---

## 9. Load Testing

### Install k6

```bash
# macOS
$ brew install k6

# Linux
$ sudo gpg -k
$ sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
$ echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/k6.list
$ sudo apt-get update && sudo apt-get install k6
```

### Test 1: Smoke Test (verify it works)

```bash
$ cd k6-tests
$ k6 run smoke-test.js -e BASE_URL=http://<YOUR-ALB-DNS>
```

Open CloudWatch while this runs. CPU should be low (~10-20%).

---

### Test 2: Load Test (trigger auto scaling)

```bash
$ k6 run load-test.js -e BASE_URL=http://<YOUR-ALB-DNS>
```

**What to watch simultaneously (open these in separate browser tabs):**

| AWS Console Page | What to Look For |
|---|---|
| EC2 → Auto Scaling Groups → Activity | Scale-out event appearing |
| CloudWatch → Alarms | CPUUtilization alarm going from OK → ALARM |
| EC2 → Target Groups → Targets | New instance appearing, going healthy |
| EC2 → Instances | New instance launching |
| k6 terminal output | `instanceId` rotating between two instances |

**Timeline of what happens:**
```
00:00 - Load test starts (50 users)
02:00 - CPU crosses 60%
02:00 - CloudWatch alarm fires
02:00 - ASG decides to launch new instance
03:00 - New EC2 instance starts booting (~1 min)
03:30 - Spring Boot app starts (~30 sec)
04:00 - ALB health check passes, instance becomes "healthy"
04:00 - ALB starts routing traffic to both instances
04:00 - k6 output shows TWO different instanceIds
```

---

### Test 3: Spike Test (flash sale simulation)

```bash
$ k6 run spike-test.js -e BASE_URL=http://<YOUR-ALB-DNS>
```

This simulates 500 users hitting the API within 10 seconds.
Observe: initial latency spike → stock sells out → auto scaling kicks in (too late for the spike, but valuable learning).

---

### Reset Stock for More Tests

After stock runs out, reset it via Product Service:

```bash
# SSH into the product-service EC2
$ ssh -i your-key.pem ec2-user@<PRODUCT_EC2_IP>

# Connect to MySQL and reset stock
[EC2] docker exec -it mysql mysql -u root -ppassword flashsale

mysql> UPDATE products SET stock = 100 WHERE id = 1;
mysql> UPDATE products SET stock = 50  WHERE id = 2;
mysql> UPDATE products SET stock = 200 WHERE id = 3;
mysql> SELECT * FROM products;
mysql> exit;
```

---

## 10. Understanding Auto Scaling Concepts

### Cooldown Periods

After the ASG launches a new instance (scale-out), it waits for a **cooldown period**
before evaluating whether to scale out again.

**Default cooldown: 300 seconds (5 minutes)**

Why? Because new instances take time to start and begin handling traffic.
Without cooldown, the ASG might keep launching instances while the first
new one is still booting.

**In AWS Console:**
Auto Scaling Group → Advanced configurations → Default instance warmup

Set this to **120 seconds** (2 minutes) for our setup since Spring Boot
starts in ~30 seconds but we want a safety buffer.

---

### Startup Delay (Instance Warm-Up)

Timeline from "scale-out decision" to "instance serving traffic":
```
Scale decision made         → +0:00
EC2 instance starts booting → +0:30 to +1:30  (EC2 startup)
User data script runs       → +1:30 to +2:00  (your startup script)
Spring Boot starts          → +2:00 to +2:30  (app initialization)
Health check passes (×2)    → +2:30 to +3:30  (30s interval × 2 checks)
Instance marked healthy     → +3:30 to +4:00
ALB starts routing traffic  → +4:00
```

**Total: ~3-4 minutes from decision to serving traffic.**

This is the "scaling lag" problem in real systems. Solutions:
- Pre-warm instances before expected load (increase desired before the event)
- Use Lambda for instant scaling (but more complex)
- Keep instances warm (min=2 instead of min=1)

---

### Health Checks

There are TWO levels of health checks working together:

**1. EC2 Health Check (ASG level)**
- Checks if the EC2 instance itself is running
- If instance crashes, ASG replaces it
- This is basic infrastructure health

**2. ELB Health Check (ALB level)**
- Checks if the APPLICATION is responding
- Hits `GET /actuator/health` every 30 seconds
- If it returns non-200, instance is marked unhealthy
- ALB stops sending traffic to unhealthy instances
- ASG also replaces instances that fail ELB health checks

**This is why we include Spring Actuator** in both services.

---

### Scale-In Protection

Scale-in (removing instances) is intentionally conservative:
- Scale-out alarm: fires after 2 minutes of high CPU
- Scale-in alarm: fires after 5+ minutes of low CPU

Why the asymmetry? It's better to keep an extra instance running (small cost)
than to remove it too aggressively and then spike again.

**Scale-in cooldown:** After removing an instance, wait before removing another.

---

### Target Tracking vs Step Scaling

**Target Tracking (what we use):**
- You say: "Keep CPU around 60%"
- AWS figures out when and how many to scale
- Simpler, AWS manages the math

**Step Scaling (alternative):**
- You say: "If CPU 60-70%, add 1. If 70-80%, add 2. If 80%+, add 3"
- More control, more configuration

For learning, target tracking is perfect.

---

## QUICK REFERENCE

| What | Value |
|---|---|
| Order Service port | 8080 |
| Product Service port | 8081 |
| Health check path | /actuator/health |
| Scale-out threshold | CPU > 60% for 2 min |
| Scale-in threshold | CPU < 60% for 5 min |
| Min instances | 1 |
| Max instances | 2 |
| k6 smoke test | `k6 run smoke-test.js` |
| k6 load test | `k6 run load-test.js -e BASE_URL=http://YOUR-ALB` |
| k6 spike test | `k6 run spike-test.js -e BASE_URL=http://YOUR-ALB` |

---

## TROUBLESHOOTING

**"Out of stock" immediately:**
→ Reset stock via MySQL (see section 9)

**Health check failing:**
→ SSH into instance, run `curl http://localhost:8080/actuator/health`
→ If 404: app isn't running. Check `~/app.log`
→ If connection refused: app crashed or wrong port

**Auto scaling not triggering:**
→ Check CloudWatch alarm - is CPU actually above 60%?
→ Try spike-test.js instead of load-test.js for higher CPU pressure
→ Check ASG Activity tab for any error messages

**New instance not serving traffic:**
→ Check Target Group → Targets. Is the new instance "healthy"?
→ If "initial": still doing health checks (wait 60s)
→ If "unhealthy": app failed to start. SSH in and check logs

**k6 shows all traffic to one instance:**
→ Normal at first! New instance takes ~4min to become healthy
→ Once healthy, ALB uses round-robin by default
