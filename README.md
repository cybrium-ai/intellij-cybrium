# Cybrium Security Scanner — IntelliJ Plugin

Real-time SAST, secrets, IaC, supply-chain, and CIA posture scoring for IntelliJ IDEA, WebStorm, PyCharm, GoLand, and all JetBrains IDEs.

## Features

- **Scan on save** — inline annotations with severity highlighting
- **1,815 rules** across 75+ languages
- **296 secret patterns** + entropy detection
- **CyTriad CIA scoring** (Confidentiality, Integrity, Availability)
- **Supply chain** — CVE + typosquat + license compliance
- **Repo health** — 14 security hygiene checks
- **Tool window** — findings tree grouped by severity

## Prerequisites

```bash
brew tap cybrium-ai/cli
brew install cyscan
```

## Install

### From JetBrains Marketplace
Search for "Cybrium" in Settings > Plugins > Marketplace.

### From disk
1. Download the latest `.zip` from [Releases](https://github.com/cybrium-ai/intellij-cybrium/releases)
2. Settings > Plugins > Install Plugin from Disk

## Usage

- **Tools > Cybrium > Scan Current File**
- **Tools > Cybrium > Scan Project**
- **Tools > Cybrium > Supply Chain Scan**
- **Tools > Cybrium > Repository Health Check**
- **Tools > Cybrium > CyTriad CIA Summary**

## Settings

Settings > Tools > Cybrium:
- cyscan binary path (auto-detected from PATH)
- Scan on save toggle
- Minimum severity filter
- CyTriad CIA summary toggle

## Build from source

```bash
./gradlew buildPlugin
# Output: build/distributions/intellij-cybrium-0.1.0.zip
```

## License

Apache 2.0
