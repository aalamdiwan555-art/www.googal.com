#!/bin/bash
# Run this once in the Shell to push the latest code to GitHub
set -e
REMOTE=$(git remote get-url origin)
AUTHED=$(echo "$REMOTE" | sed "s|https://|https://$GITHUB_PERSONAL_ACCESS_TOKEN@|")
git push "$AUTHED" HEAD:main
echo "✅ Pushed to GitHub successfully!"
