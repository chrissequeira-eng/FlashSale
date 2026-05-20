# Flash Sale System — AWS Deployment Guide (Docker on EC2)

> Everything runs inside Docker on EC2. No Maven, no Java installation needed on the server.
> Commands marked `[LOCAL]` run on your Windows machine. Commands marked `[EC2]` run on your EC2 instance via SSH.

---

## TABLE OF CONTENTS

1. [Prepare Docker Images Locally](#1-prepare-images)
2. [Launch EC2 Instances](#2-launch-ec2)
3. [Set Up Product EC2 (MySQL + Product Service)](#3-product-ec2)
4. [Set Up Order EC2 (Order Service)](#4-order-ec2)
5. [Test Both Services Are Talking](#5-test-services)
6. [Create AMI from Order EC2](#6-create-ami)
7. [Create Launch Template](#7-launch-template)
8. [Create Application Load Balancer](#8-create-alb)
9. [Create Auto Scaling Group](#9-create-asg)
10. [Run Load Tests and Observe Scaling](#10-load-testing)
11. [Auto Scaling Concepts Explained](#11-concepts)

---

## 1. Prepare Docker Images Locally

We build images on your local machine and push to Docker Hub.
EC2 instances just pull and run — no build step on EC2 at all.

### 1a. Create a free Docker Hub account
Go to https://hub.docker.com and sign up.

### 1b. Login locally

```powershell
[LOCAL] docker login
```

### 1c. Build and push Product Service

```powershell
[LOCAL] cd flash-sale-system

[LOCAL] docker build -t YOUR_DOCKERHUB_USERNAME/flashsale-product:latest ./product-service
[LOCAL] docker push YOUR_DOCKERHUB_USERNAME/flashsale-product:latest
```

### 1d. Build and push Order Service

```powershell
[LOCAL] docker build -t YOUR_DOCKERHUB_USERNAME/flashsale-order:latest ./order-service
[LOCAL] docker push YOUR_DOCKERHUB_USERNAME/flashsale-order:latest
```

Verify both images appear at: https://hub.docker.com/repositories

---

## 2. Launch EC2 Instances

Go to **AWS Console → EC2 → Launch Instance** and launch TWO instances.

| Setting | Value |
|---|---|
| AMI | Amazon Linux 2023 (free tier) |
| Instance type | t2.micro (free tier) |
| Key pair | Create new → save the .pem file |
| Storage | 8 GB default |

### Security Group Rules (one group, used by both instances)

| Type | Protocol | Port | Source | Purpose |
|---|---|---|---|---|
| SSH | TCP | 22 | My IP | SSH access |
| Custom TCP | TCP | 8080 | Anywhere | Order Service |
| Custom TCP | TCP | 8081 | Anywhere | Product Service |
| Custom TCP | TCP | 80 | Anywhere | ALB listener |
| Custom TCP | TCP | 3306 | Anywhere | MySQL |

Name your instances:
- `flashsale-product` — runs MySQL + Product Service
- `flashsale-order`   — runs Order Service (becomes AMI base)

---

## 3. Set Up Product EC2

SSH in:

```powershell
[LOCAL] ssh -i "your-key.pem" ec2-user@<PRODUCT_EC2_PUBLIC_IP>
```

### 3a. Install Docker

```bash
[EC2] sudo yum update -y
[EC2] sudo yum install docker -y
[EC2] sudo service docker start
[EC2] sudo usermod -aG docker ec2-user
[EC2] newgrp docker
[EC2] docker --version
```

### 3b. Start MySQL

```bash
[EC2] docker run -d \
  --name mysql \
  --restart unless-stopped \
  -e MYSQL_ROOT_PASSWORD=password \
  -e MYSQL_DATABASE=flashsale \
  -p 3306:3306 \
  mysql:8.0

# Wait 30 seconds for MySQL to initialize
[EC2] sleep 30
[EC2] docker logs mysql | tail -5
# Look for: ready for connections
```

### 3c. Run Product Service

```bash
# Using --network host so product-service can reach mysql via localhost
[EC2] docker run -d \
  --name flashsale-product \
  --restart unless-stopped \
  --network host \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/flashsale?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  -e SPRING_DATASOURCE_USERNAME=root \
  -e SPRING_DATASOURCE_PASSWORD=password \
  YOUR_DOCKERHUB_USERNAME/flashsale-product:latest
```

### 3d. Verify

```bash
# Wait ~30 seconds for Spring Boot to start
[EC2] sleep 30
[EC2] curl http://localhost:8081/products
# Should return JSON list of 3 products

[EC2] curl http://localhost:8081/actuator/health
# Should return {"status":"UP"}
```

### 3e. Save the Private IP — needed for Order Service

```bash
[EC2] curl http://169.254.169.254/latest/meta-data/local-ipv4
# Example: 172.31.47.149
# COPY THIS — you will paste it in the next section
```

---

## 4. Set Up Order EC2

SSH in:

```powershell
[LOCAL] ssh -i "your-key.pem" ec2-user@<ORDER_EC2_PUBLIC_IP>
```

### 4a. Install Docker

```bash
[EC2] sudo yum update -y
[EC2] sudo yum install docker -y
[EC2] sudo service docker start
[EC2] sudo usermod -aG docker ec2-user
[EC2] newgrp docker
```

### 4b. Run Order Service

```bash
# Get this instance's ID
[EC2] INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)

# Replace 172.31.XX.XX with the private IP you saved from step 3e
[EC2] docker run -d \
  --name flashsale-order \
  --restart unless-stopped \
  -p 8080:8080 \
  -e PRODUCT_SERVICE_URL=http://172.31.XX.XX:8081 \
  -e INSTANCE_ID=$INSTANCE_ID \
  YOUR_DOCKERHUB_USERNAME/flashsale-order:latest
```

### 4c. Verify

```bash
[EC2] sleep 20
[EC2] curl http://localhost:8080/actuator/health
# Should return {"status":"UP"}

[EC2] curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productId": 1, "quantity": 1}'
# Should return {"status":"SUCCESS",...}
```

---

## 5. Test From Your Local Machine

```powershell
# Test Product Service via public IP
[LOCAL] curl http://<PRODUCT_EC2_PUBLIC_IP>:8081/products

# Test Order Service via public IP
[LOCAL] curl -X POST http://<ORDER_EC2_PUBLIC_IP>:8080/orders `
  -H "Content-Type: application/json" `
  -d '{\"productId\": 1, \"quantity\": 1}'
```

Both must work before continuing.

---

## 6. Create AMI from Order EC2

1. Go to **EC2 → Instances**
2. Select `flashsale-order`
3. Click **Actions → Image and templates → Create image**
4. Settings:
   - Image name: `flashsale-order-v1`
   - No reboot: ✅
5. Click **Create image**
6. Go to **EC2 → AMIs** — wait until status = `available` (3-5 min)

The AMI captures: OS + Docker installed + your Order image already pulled.
New ASG instances boot from this — no downloading or building needed.

---

## 7. Create Launch Template

**EC2 → Launch Templates → Create launch template**

| Setting | Value |
|---|---|
| Name | `flashsale-order-lt` |
| AMI | `flashsale-order-v1` (your AMI) |
| Instance type | `t2.micro` |
| Key pair | Your existing key pair |
| Security Group | Your existing security group |

### Advanced Details → User Data

This script runs every time ASG launches a new instance:

```bash
#!/bin/bash

# Get this instance's ID
INSTANCE_ID=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)

# REPLACE with your actual Product EC2 private IP from step 3e
PRODUCT_PRIVATE_IP="172.31.XX.XX"

# Start Docker
sudo service docker start

# Pull latest image
docker pull YOUR_DOCKERHUB_USERNAME/flashsale-order:latest

# Clean up any old container
docker stop flashsale-order 2>/dev/null || true
docker rm flashsale-order 2>/dev/null || true

# Start Order Service
docker run -d \
  --name flashsale-order \
  --restart unless-stopped \
  -p 8080:8080 \
  -e PRODUCT_SERVICE_URL=http://${PRODUCT_PRIVATE_IP}:8081 \
  -e INSTANCE_ID=${INSTANCE_ID} \
  YOUR_DOCKERHUB_USERNAME/flashsale-order:latest
```

Click **Create launch template**.

---

## 8. Create Application Load Balancer

### 8a. Create Target Group First

**EC2 → Target Groups → Create target group**

| Setting | Value |
|---|---|
| Target type | Instances |
| Name | `flashsale-order-tg` |
| Protocol | HTTP |
| Port | 8080 |
| VPC | Default VPC |
| Health check path | `/actuator/health` |
| Healthy threshold | 2 |
| Unhealthy threshold | 2 |
| Interval | 30 seconds |

Click **Create target group** — do NOT add instances manually.

### 8b. Create the ALB

**EC2 → Load Balancers → Create Load Balancer → Application Load Balancer**

| Setting | Value |
|---|---|
| Name | `flashsale-alb` |
| Scheme | Internet-facing |
| VPC | Default VPC |
| Availability Zones | Select ALL available AZs |
| Security Group | Your existing security group |
| Listener | HTTP port 80 → forward to `flashsale-order-tg` |

Click **Create load balancer**.

Save the **DNS name** — looks like:
`flashsale-alb-123456.ap-south-1.elb.amazonaws.com`

---

## 9. Create Auto Scaling Group

**EC2 → Auto Scaling Groups → Create Auto Scaling group**

**Step 1 — Name and template:**
- Name: `flashsale-order-asg`
- Launch template: `flashsale-order-lt`

**Step 2 — Network:**
- VPC: Default
- Availability Zones: Same as ALB (select all)

**Step 3 — Load balancing:**
- Attach to existing load balancer ✅
- Target groups: `flashsale-order-tg`
- Health check type: ELB ✅

**Step 4 — Group size:**
- Desired: `1`
- Minimum: `1`
- Maximum: `2`

**Step 5 — Scaling policy:**
- Type: Target tracking
- Metric: Average CPU Utilization
- Target value: `60`

**Step 6 — Instance warmup:**
- Default instance warmup: `120 seconds`

Click **Create Auto Scaling group**.

### Verify health:
EC2 → Target Groups → `flashsale-order-tg` → Targets tab
Wait 2 minutes → instance should show `healthy`

---

## 10. Run Load Tests and Observe Scaling

```powershell
# Replace with your actual ALB DNS name
[LOCAL] k6 run k6-tests/smoke-test.js -e BASE_URL=http://flashsale-alb-123456.ap-south-1.elb.amazonaws.com

[LOCAL] k6 run k6-tests/load-test.js -e BASE_URL=http://flashsale-alb-123456.ap-south-1.elb.amazonaws.com

[LOCAL] k6 run k6-tests/spike-test.js -e BASE_URL=http://flashsale-alb-123456.ap-south-1.elb.amazonaws.com
```

### Open these tabs while load test runs:

| AWS Console Tab | Watch For |
|---|---|
| EC2 → Auto Scaling Groups → Activity | New scale-out event |
| CloudWatch → Alarms | CPU alarm: OK → ALARM |
| EC2 → Target Groups → Targets | New instance → healthy |
| EC2 → Instances | New instance launching |
| k6 terminal | Two different instanceIds alternating |

### What happens (timeline):

```
00:00  Load test starts
01:00  CPU climbs above 60%
02:00  CloudWatch alarm fires
02:00  ASG launches new instance from AMI
02:30  EC2 boots
03:00  User Data script runs → Docker starts container
03:20  Spring Boot starts inside container
04:00  Health check passes twice → instance = healthy
04:00  ALB routes traffic to BOTH instances
04:00  k6 output shows two different instanceIds  ← load balancing confirmed
```

### Reset stock between tests:

```powershell
[LOCAL] ssh -i "your-key.pem" ec2-user@<PRODUCT_EC2_PUBLIC_IP>
[EC2]   docker exec -it mysql mysql -u root -ppassword flashsale -e "UPDATE products SET stock = 10000 WHERE id = 1;"
```

---

## 11. Auto Scaling Concepts Explained

### Why Docker on EC2 instead of building on EC2?
Building (Maven compile) takes 2-3 minutes per instance. With Docker Hub,
a new instance just runs `docker pull` and starts in seconds. AMI also
caches the image so pull is near-instant.

### Cooldown Period
After launching a new instance, ASG waits before launching another.
Prevents launching 5 instances when only 1 more is needed.

### Startup Delay (why scaling takes ~4 minutes)
```
EC2 boots              → ~60s
User Data script runs  → ~30s
Spring Boot starts     → ~20s
Health checks pass ×2  → ~60s
Total                  → ~3-4 minutes
```

### Two Levels of Health Checks
- **EC2 check** — is the machine alive? ASG replaces dead machines.
- **ELB check** — is the app responding on /actuator/health? ALB stops
  routing to sick instances. Both run simultaneously.

### Scale-Out vs Scale-In Speed
- Scale-out: triggers after 2 minutes of CPU > 60% (fast, to handle load)
- Scale-in: triggers after 5+ minutes of CPU < 60% (slow, to avoid thrashing)

---

## TROUBLESHOOTING

**New ASG instance unhealthy:**
```bash
ssh -i your-key.pem ec2-user@<NEW_INSTANCE_IP>
docker ps -a                          # Is container running?
docker logs flashsale-order           # Any startup errors?
sudo cat /var/log/cloud-init-output.log  # Did User Data script run?
curl http://localhost:8080/actuator/health
```

**Order Service can't reach Product Service:**
```bash
# From Order EC2
curl http://<PRODUCT_PRIVATE_IP>:8081/products
# If this fails, check Security Group allows port 8081 between instances
```

**Auto scaling not triggering:**
- Run spike-test.js for more aggressive load
- Check CloudWatch → Alarms → is CPU actually above 60%?
- Check ASG → Activity tab for any error messages

**k6 showing only one instanceId:**
- Normal for first 4 minutes while second instance boots
- Once second instance is healthy, ALB round-robins between both
