# Releasing 0.1.0

Immutability Checker 0.1.0 is a technical-preview release. One exact commit and
one annotated `v0.1.0` tag cover both published modules:

- `io.github.j-util:immutability-checker:0.1.0`
- `io.github.j-util:immutability-checker-processor:0.1.0`

`io.github.j-util:immutability-checker-build:0.1.0` is the unpublished reactor
aggregator and must never be uploaded.

## Preflight

Start only from a clean `main` branch whose exact commit has green Temurin Java
8 and Java 26 CI:

```shell
git status --short
git branch --show-current
git rev-parse HEAD
```

Protect the Central settings file and confirm that a release signing key is
available. Do not print or copy credentials or private key material:

```shell
chmod 600 /Users/karenbarseghyan/.m2/settings-j-util.xml
gpg --list-secret-keys --keyid-format long
```

Confirm the Central activation boundary before deployment. With the
`central-publish` profile active, both module checks must print `true` by
default and `false` only with the explicit override:

```shell
./mvnw --quiet --file immutability-checker/pom.xml \
  -Pcentral-publish help:evaluate \
  -Dexpression=central.skipPublishing -DforceStdout
./mvnw --quiet --file immutability-checker-processor/pom.xml \
  -Pcentral-publish help:evaluate \
  -Dexpression=central.skipPublishing -DforceStdout
./mvnw --quiet --file immutability-checker/pom.xml \
  -Pcentral-publish -Dcentral.skipPublishing=false help:evaluate \
  -Dexpression=central.skipPublishing -DforceStdout
./mvnw --quiet --file immutability-checker-processor/pom.xml \
  -Pcentral-publish -Dcentral.skipPublishing=false help:evaluate \
  -Dexpression=central.skipPublishing -DforceStdout
./mvnw --batch-mode --no-transfer-progress \
  -Pcentral-publish help:effective-pom \
  -Doutput=target/central-skipped-effective-pom.xml
./mvnw --batch-mode --no-transfer-progress \
  -Pcentral-publish -Dcentral.skipPublishing=false help:effective-pom \
  -Doutput=target/central-enabled-effective-pom.xml
```

Inspect both effective POMs. The active Central configuration must use
`skipPublishing=true` throughout the first and `skipPublishing=false`
throughout the second. The root POM's ordinary install/deploy skips and its
`immutability-checker-build` exclusion must remain present in both. The local
bundle check below independently confirms the intended component inventory.

## Verification

Select Temurin JDK 8, confirm it with `./mvnw --version`, and run:

```shell
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress dependency:tree
```

Repeat the same two commands under Temurin JDK 26. Then run the unsigned
signing-profile dry run:

```shell
./mvnw --batch-mode --no-transfer-progress \
  -Prelease -Dgpg.skip=true clean verify
```

## Local Central bundle

Build the release-only, no-upload bundle with signing skipped to inspect its
base topology:

```shell
./mvnw --batch-mode --no-transfer-progress \
  -Prelease -Dgpg.skip=true -Dmaven.deploy.skip=true \
  -Dcentral.skipPublishing=true clean deploy
unzip -l target/central-publishing/central-bundle.zip
```

The explicit `central.skipPublishing=true` activates the local bundle profile,
while `maven.deploy.skip=true` suppresses every ordinary module deploy goal.
The `central-publish` profile is deliberately absent, so this command does not
contact Central. The ZIP must contain exactly two Maven component paths. Each
must contain its POM, main JAR, source JAR, Javadoc JAR, plus MD5, SHA-1,
SHA-256, and SHA-512 checksums for each of those four files. It must not contain
an `immutability-checker-build` path.

After the GPG preflight succeeds, repeat the local build without
`-Dgpg.skip=true`:

```shell
./mvnw --batch-mode --no-transfer-progress \
  -Prelease -Dmaven.deploy.skip=true \
  -Dcentral.skipPublishing=true clean deploy
unzip -l target/central-publishing/central-bundle.zip
```

The signed check requires the POM, main JAR, source JAR, and Javadoc JAR
signature for each coordinate. The build fails if any of those eight `.asc`
files is missing.

## Central publication

`central.skipPublishing=true` is the deliberate safety default in all three
POMs. A real deployment must activate both release profiles and override that
default explicitly:

```shell
./mvnw \
  --settings /Users/karenbarseghyan/.m2/settings-j-util.xml \
  --batch-mode \
  --no-transfer-progress \
  -Prelease,central-publish \
  -Dcentral.skipPublishing=false \
  clean deploy
```

The Central plugin has `autoPublish=false`. After upload and successful Central
validation, review the deployment in the Central Portal, confirm that it
contains both published coordinates and no aggregator component, and publish
it manually.

## Tag and GitHub prerelease

Only after the release commit is final and its exact CI is green, create the
annotated tag and push `main` and that exact tag:

```shell
git tag -a v0.1.0 \
  -m "Immutability Checker 0.1.0 — Technical Preview"
git push origin main
git push origin v0.1.0
```

Create the GitHub prerelease only after the tag exists remotely, Central
validation succeeds, and the deployment is confirmed to contain both expected
coordinates:

```shell
gh release create v0.1.0 \
  --repo j-util/immutability-checker \
  --title "Immutability Checker 0.1.0 — Technical Preview" \
  --prerelease \
  --generate-notes
```

## Post-publication verification

In Maven Central, confirm that both coordinates expose their module POM, main
JAR, source JAR, Javadoc JAR, and signatures. From a clean external consumer
and a fresh Maven local repository, use `immutability-checker` with `provided`
scope (or Gradle `compileOnly`) and put `immutability-checker-processor` only on
the annotation-processor path. Compile one fixture that must pass and one that
must fail with an immutability diagnostic; confirm neither artifact becomes an
application runtime dependency.
