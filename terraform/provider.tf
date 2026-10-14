# ────────────────────────────────────────────────────────
# 1️⃣  PROVIDERS
# ────────────────────────────────────────────────────────
terraform {
  required_version = ">= 1.3"
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.27"
    }
  }
}

# Personal access token – put it in terraform.tfvars or an env var
variable "do_token" {
  description = "DigitalOcean API token with read/write permissions for the cluster"
  type        = string
  sensitive   = true
}

provider "digitalocean" {
  token = var.do_token
}

# ────────────────────────────────────────────────────────
# 2️⃣  GET CLUSTER DETAILS
# ────────��───────────────────────────────────────────────
data "digitalocean_kubernetes_cluster" "k8s" {
  name = "k8s-1-36-3-do-5-blr1-1791312051870"
}

# ────────────────────────────────────────────────────────
# 3️⃣  CONFIGURE KUBERNETES PROVIDER USING CLUSTER KUBECONFIG
# ────────────────────────────────────────────────────────
provider "kubernetes" {
  host                   = data.digitalocean_kubernetes_cluster.k8s.endpoint
  token                  = data.digitalocean_kubernetes_cluster.k8s.kube_config[0].token
  cluster_ca_certificate = base64decode(
    data.digitalocean_kubernetes_cluster.k8s.kube_config[0].cluster_ca_certificate
  )
}

# ────────────────────────────────────────────────────────
# 4️⃣  DEPLOYMENTS
# ────────────────────────────────────────────────────────
resource "kubernetes_deployment" "spring_test" {
  metadata {
    name      = "spring-test"
    namespace = "default"
  }

  spec {
    replicas = 2

    selector {
      match_labels = { app = "spring-test" }
    }

    template {
      metadata {
        labels = { app = "spring-test" }
      }
      spec {
        container {
          name  = "spring-test"
          image = "tinumistry/spring_test_001:latest"
          image_pull_policy = "Always"

          port {
            container_port = 8080
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "spring_test_service" {
  metadata {
    name      = "spring-test-service"
    namespace = "default"
  }

  spec {
    type = "LoadBalancer"

    selector = { app = "spring-test" }

    port {
      protocol    = "TCP"
      port        = 80
      target_port = 8080
    }
  }
}
