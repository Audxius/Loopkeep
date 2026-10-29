#!/usr/bin/env bash
# Guided one-time setup for signed releases:
#   1. checks prerequisites
#   2. creates a release keystore OUTSIDE the repository
#   3. stores it as GitHub Actions secrets (automatically with the gh CLI, or prints manual steps)
#   4. optionally pushes main and the first tag, which triggers the first release
#   5. explains how to install and update the app with Obtainium
set -euo pipefail

KEYSTORE_DIR="${LOOPKEEP_KEYSTORE_DIR:-$HOME/.loopkeep-signing}"
KEYSTORE="$KEYSTORE_DIR/loopkeep-release.jks"
ALIAS="loopkeep"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
err()  { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
ask()  { local reply; read -r -p "$1 [y/N] " reply; [[ "$reply" =~ ^[Yy]$ ]]; }

cd "$(git rev-parse --show-toplevel 2>/dev/null || dirname "$0")"

bold "== Loopkeep release setup =="
echo

# --- 1. prerequisites ------------------------------------------------------------------
missing=0
if command -v git >/dev/null; then ok "git"; else err "git is not installed"; missing=1; fi
if command -v keytool >/dev/null; then
    ok "keytool (from a JDK)"
else
    err "keytool not found. Install a JDK 17+ (e.g. Temurin, or 'sudo apt install openjdk-17-jdk')."
    missing=1
fi
GH_OK=0
if command -v gh >/dev/null; then
    ok "gh CLI"
    if gh auth status >/dev/null 2>&1; then
        ok "gh is logged in"
        GH_OK=1
    else
        warn "gh is installed but not logged in. Run 'gh auth login' to let this script set secrets for you."
    fi
else
    warn "gh CLI not found (https://cli.github.com). Secrets will need to be added by hand."
fi
[ "$missing" -eq 0 ] || { err "Install the missing tools and run this again."; exit 1; }

REPO=""
if url=$(git remote get-url origin 2>/dev/null); then
    REPO=$(echo "$url" | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')
    ok "GitHub repository: $REPO"
else
    warn "No 'origin' remote. Add one with: git remote add origin git@github.com:<you>/<repo>.git"
fi
echo

# --- 2. keystore ---------------------------------------------------------------------------
read_password() {
    local prompt="$1" pw pw2
    while true; do
        read -r -s -p "$prompt (min. 6 characters): " pw; echo
        [ "${#pw}" -ge 6 ] || { err "Too short."; continue; }
        read -r -s -p "Repeat: " pw2; echo
        [ "$pw" = "$pw2" ] && break
        err "Passwords don't match."
    done
    REPLY_PW="$pw"
}

if [ -f "$KEYSTORE" ]; then
    ok "Using existing keystore: $KEYSTORE"
    read -r -s -p "Keystore password: " STORE_PW; echo
    if ! keytool -list -keystore "$KEYSTORE" -storepass "$STORE_PW" -alias "$ALIAS" >/dev/null 2>&1; then
        err "Wrong password, or the keystore has no '$ALIAS' key."
        exit 1
    fi
    read -r -s -p "Key password (Enter if same as keystore password): " KEY_PW; echo
    KEY_PW="${KEY_PW:-$STORE_PW}"
else
    bold "Creating the release signing key"
    echo "It will be stored at: $KEYSTORE (outside the repository, never committed)."
    read_password "Choose a keystore password"; STORE_PW="$REPLY_PW"
    # PKCS12 keystores use one password for the store and the key.
    KEY_PW="$STORE_PW"
    read -r -p "Name to put in the certificate [Loopkeep]: " CN; CN="${CN:-Loopkeep}"
    mkdir -p "$KEYSTORE_DIR"
    chmod 700 "$KEYSTORE_DIR"
    keytool -genkeypair -v -storetype PKCS12 -keystore "$KEYSTORE" -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 36500 \
        -storepass "$STORE_PW" -keypass "$KEY_PW" -dname "CN=$CN" >/dev/null 2>&1
    chmod 600 "$KEYSTORE"
    ok "Created $KEYSTORE"
fi
echo

warn "##########################################################################"
warn "#  BACK UP THIS KEYSTORE AND ITS PASSWORD NOW (password manager, USB...).  #"
warn "#  $KEYSTORE"
warn "#  If you lose either, future releases can't update the installed app:    #"
warn "#  you would have to uninstall it (its settings reset; your folder of      #"
warn "#  notes and videos is not affected) and install again.                    #"
warn "##########################################################################"
echo

# --- 3. GitHub secrets ---------------------------------------------------------------------
KEYSTORE_B64=$(base64 < "$KEYSTORE" | tr -d '\n')
if [ "$GH_OK" -eq 1 ] && [ -n "$REPO" ] && ask "Store the signing secrets in $REPO now?"; then
    printf '%s' "$KEYSTORE_B64" | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
    printf '%s' "$STORE_PW"     | gh secret set RELEASE_KEYSTORE_PASSWORD --repo "$REPO"
    printf '%s' "$ALIAS"        | gh secret set RELEASE_KEY_ALIAS --repo "$REPO"
    printf '%s' "$KEY_PW"       | gh secret set RELEASE_KEY_PASSWORD --repo "$REPO"
    ok "Secrets stored"
else
    B64_FILE="$KEYSTORE_DIR/keystore.base64.txt"
    printf '%s' "$KEYSTORE_B64" > "$B64_FILE"
    chmod 600 "$B64_FILE"
    bold "Add these secrets by hand:"
    echo "  GitHub → ${REPO:-your repo} → Settings → Secrets and variables → Actions → New repository secret"
    echo
    echo "  RELEASE_KEYSTORE_BASE64   = the entire contents of $B64_FILE"
    echo "  RELEASE_KEYSTORE_PASSWORD = the keystore password you chose"
    echo "  RELEASE_KEY_ALIAS         = $ALIAS"
    echo "  RELEASE_KEY_PASSWORD      = the same password (unless you set a different key password)"
    echo
    warn "Delete $B64_FILE once the secret is saved; it is a copy of your signing key."
fi
echo

# --- 4. first release -------------------------------------------------------------------------
if [ -n "$REPO" ]; then
    if git tag -l 'v*' | grep -q .; then
        ok "Tags already exist; use scripts/release.sh for the next release."
    elif ask "Push main and create the first release tag v0.1.0 now?"; then
        git push origin HEAD:main
        git tag -a v0.1.0 -m "Loopkeep v0.1.0"
        git push origin v0.1.0
        ok "Pushed v0.1.0. Watch the build at: https://github.com/$REPO/actions"
    else
        echo "Later, run: scripts/release.sh"
    fi
fi
echo

# --- 5. Obtainium --------------------------------------------------------------------------------
bold "Installing with Obtainium (https://obtainium.imranr.dev)"
cat <<EOF
  1. Install Obtainium on your phone.
  2. Add App → paste: https://github.com/${REPO:-<you>/<repo>}
  3. Leave the defaults. Each release has exactly one asset, loopkeep.apk.
  4. Install. Obtainium checks for new releases and offers updates.

  Public repository: works as is (simplest).
  Private repository: Obtainium needs a GitHub token. Create a fine-grained token at
  https://github.com/settings/personal-access-tokens with read-only "Contents" access to
  this repository, then paste it into Obtainium → Settings → GitHub → Personal access token.
EOF
