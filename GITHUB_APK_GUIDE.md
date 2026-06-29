# 🚀 GitHub CI/CD APK Creation System Guide

Welcome to the automated CI/CD pipeline for **AutoClicker Pro**! This repository is configured with a high-performance **GitHub Actions** system (`.github/workflows/android.yml`) that automates the building, testing, signing, and releasing of your Android APKs.

---

## 📖 Table of Contents
1. [Overview of the Pipeline](#-overview-of-the-pipeline)
2. [How to Setup & Connect to GitHub](#-how-to-setup--connect-to-github)
3. [Configuring Production Signing Secrets](#-configuring-production-signing-secrets)
4. [Running and Triggering the Builds](#-running-and-triggering-the-builds)
5. [Release Notes & Security Verification](#-release-notes--security-verification)

---

## 🛠️ Overview of the Pipeline

The pipeline is completely automated and supports:
* **Pull Request Checks**: Runs compilation checks automatically on incoming PRs to prevent broken builds.
* **Continuous Integration**: Compile a `debug` version of the APK on every push to `main` or `master` and store it securely as a build artifact.
* **Auto-Publish Release**: Pushing version tags (e.g., `v1.0.2`) automatically triggers a production-ready compile, creates a **GitHub Release**, attaches the APKs, and documents the release notes with calculated **SHA-256 checksums**.
* **Manual Control**: Customize and trigger builds manually via the **GitHub "Actions" tab** (choose `debug`, `release`, or `both` and toggling manual release publishing).

---

## 🔗 How to Setup & Connect to GitHub

To link this workspace to your GitHub account:

1. **Create a New Repository**: Create a blank, private, or public repository on [GitHub](https://github.com/new). Do *not* initialize it with a README or `.gitignore` since this project already has them.
2. **Initialize Git and Push**:
   Open a terminal in your project directory and run:
   ```bash
   git init
   git add .
   git commit -m "Initialize AutoClicker Pro with Automated GitHub CI/CD"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo-name>.git
   git push -u origin main
   ```

---

## 🔑 Configuring Production Signing Secrets

While a **Debug APK** will compile automatically out-of-the-box using the embedded `debug.keystore.base64`, a **Release APK** requires your custom private keys to ensure security and Google Play compatibility.

### Step 1: Generate a Keystore File (If you don't have one)
Run this command in your local machine terminal to generate a secure keystore file named `my-upload-key.jks`:
```bash
keytool -genkey -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```
*Note: Make a note of your **Keystore Password**, **Key Alias (upload)**, and **Key Password**.*

### Step 2: Convert your Keystore to Base64 String
To store the binary keystore securely on GitHub, convert it into a base64-encoded string:
* **macOS / Linux**:
  ```bash
  base64 -i my-upload-key.jks -o keystore_base64.txt
  ```
* **Windows (PowerShell)**:
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("my-upload-key.jks")) | Out-File -FilePath keystore_base64.txt
  ```

### Step 3: Add Secrets to Your GitHub Repository
1. Go to your GitHub Repository -> **Settings** -> **Secrets and variables** -> **Actions**.
2. Click **New repository secret** and add the following three secrets:

| Secret Name | Value Example | Description |
| :--- | :--- | :--- |
| `RELEASE_KEYSTORE_BASE64` | *The long text inside `keystore_base64.txt`* | The base64-encoded custom release keystore file. |
| `STORE_PASSWORD` | `myKeystorePassword123` | Password used to secure the keystore store file. |
| `KEY_PASSWORD` | `myKeyPassword123` | Password used to secure the key alias within the keystore. |

---

## 🎯 Running and Triggering the Builds

You can run and generate your APKs in three distinct ways:

### 1. Automatic Integration (On Push or Pull Request)
* **On push to `main`**: Automatically compiles and uploads the `autoclicker-pro-debug.apk` to your actions log dashboard.
* **On pull request**: Validates the codebase syntax, ensuring no breaking changes enter production.

### 2. Versioned Tag Release (Automated Release Deployment)
When you are ready to ship a new version to users:
1. Create and push a tag from your local computer:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
2. The pipeline detects the `v*` pattern, decodes your production signing keys, builds **both Debug and Release signed APKs**, generates release checksums, and publishes a new **GitHub Release** automatically!

### 3. Manual Workflow Dispatch (Custom Runs)
1. Navigate to the **Actions** tab inside your GitHub repository.
2. Select the **Android CI/CD - Build & Release APK** workflow on the left sidebar.
3. Click the **Run workflow** dropdown on the right.
4. Customize your build parameters:
   * **Build Type**: Choose `debug`, `release`, or `both`.
   * **Create a GitHub Release?**: Check or uncheck (creates a draft/published release instantly).
5. Click **Run workflow**.

---

## 🛡️ Release Notes & Security Verification

Each public release automatically includes **SHA-256 Integrity Verification Checksums**. 
This allows users to verify that the downloaded APK was compiled cleanly in the secure cloud container and has not been modified.

Example output displayed in your automated Release Notes:
```text
#### SHA-256 Checksums
9a12e4f073... autoclicker-pro-debug.apk
f56b27d49e... autoclicker-pro-release.apk
```

---
*Created and optimized with 🧡 by Google AI Studio Build.*
