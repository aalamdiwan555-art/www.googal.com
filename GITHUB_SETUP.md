# GitHub Upload Guide — AutoClicker Pro

## Step 1: Create a GitHub Repository

1. Go to **https://github.com/new**
2. Repository name: `autoclikcer-pro` (or any name)
3. Set to **Private** (recommended) or Public
4. Do **NOT** check "Add a README" — leave everything blank
5. Click **Create repository**

---

## Step 2: Push This Project to GitHub

Copy your new repo's URL from GitHub (e.g. `https://github.com/YOUR_USERNAME/autoclikcer-pro.git`)

Then run these commands in your Replit Shell (Tools → Shell):

```bash
git init
git add .
git commit -m "AutoClicker Pro - Ultra Fast Edition"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
git push -u origin main
```

> Replace `YOUR_USERNAME` and `YOUR_REPO` with your actual GitHub username and repo name.

---

## Step 3: GitHub Will Build Your APK Automatically

Once you push, GitHub Actions will:
1. **Automatically start building** the APK (takes ~5–8 minutes)
2. You can watch the progress at:
   `https://github.com/YOUR_USERNAME/YOUR_REPO/actions`
3. When done, click the build → scroll down → **Artifacts** section
4. Download **AutoClickerPro-APKs-xxx.zip**
5. Inside the zip: `AutoClickerPro-debug.apk` — install this on your phone

---

## Step 4: Create a Release APK (Optional — for sharing)

To create a public release with a download link:

1. Go to your repo on GitHub
2. Click **Actions** tab
3. Click **Android CI/CD - Build & Release APK**
4. Click **Run workflow** (top right)
5. Set Build Type = `debug`, check **Create a GitHub Release**
6. Click **Run workflow**

After it finishes, go to **Releases** on the right side of your repo to find the downloadable APK link.

---

## Step 5: Auto-Build on Every Code Change

Every time you push new code to `main`, GitHub will automatically rebuild a fresh APK. No manual steps needed.

---

## Authentication (if git push asks for password)

GitHub no longer accepts passwords. Use a **Personal Access Token**:

1. Go to: https://github.com/settings/tokens/new
2. Name: `replit-push`
3. Expiration: 90 days
4. Scopes: check **repo**
5. Click **Generate token** — copy it immediately
6. Use this token as your password when `git push` asks

---

## Summary of What Gets Built

| File | Description |
|------|-------------|
| `AutoClickerPro-debug.apk` | Debug build — install directly on your phone for testing |
| `AutoClickerPro-release.apk` | Signed release build (only if you add keystore secrets) |
