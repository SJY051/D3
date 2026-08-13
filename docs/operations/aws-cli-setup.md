# AWS CLI developer profile

Owner: D³ team

Status: Developer authentication baseline; deployment authorization is separate

Last verified: 2026-08-14 with AWS CLI 2.35.14 and the assigned IAM account

This guide creates a local `d3` profile for the bootcamp AWS account. It does not create cloud resources, grant permissions, or configure GitHub Actions.

## Fixed bindings

| Setting | Value |
|---|---|
| AWS account | `811221506617` |
| Region | `ap-northeast-2` (Seoul) |
| Local profile | `d3` |
| Identity type | Per-member IAM user |

Each teammate has a different IAM user name and `UserId`. Verify the account and IAM-user ARN pattern rather than copying another member's identity.

## One-time setup

1. Install AWS CLI v2.32.0 or later and confirm the version.

   ```bash
   aws --version
   ```

2. Store the project defaults in a named profile. Keep personal or older accounts in separate profiles.

   ```bash
   aws configure set region ap-northeast-2 --profile d3
   aws configure set output json --profile d3
   ```

3. Start browser authentication and sign in with the assigned IAM console URL, user name, password, and MFA when required.

   ```bash
   aws login --profile d3
   ```

   The IAM administrator must allow local-development sign-in through `SignInLocalDevelopmentAccess`. Ask the team lead or account administrator if AWS denies this step.

4. Verify the resulting identity before running any project AWS command.

   ```bash
   aws sts get-caller-identity \
     --profile d3 \
     --query '{Account:Account,Arn:Arn}' \
     --output json
   ```

   Completion requires both of the following:

   - `Account` is exactly `811221506617`.
   - `Arn` starts with `arn:aws:iam::811221506617:user/` and names the IAM user assigned to you.

Stop if either value differs. Log out and repeat the browser login with the assigned account.

## Start a project shell

First remove static AWS credentials inherited from another account without printing their values.

```bash
for name in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN; do
  if printenv "$name" >/dev/null; then
    echo "Clearing inherited $name"
    unset "$name"
  fi
done
```

Then select the project profile and region for the current shell.

```bash
export AWS_PROFILE=d3
export AWS_REGION=ap-northeast-2
export AWS_DEFAULT_REGION=ap-northeast-2

aws sts get-caller-identity \
  --query '{Account:Account,Arn:Arn}' \
  --output json
```

Run the identity check at the start of every AWS work session and before any mutating command. Scripts and documentation should still use explicit account, region, and resource bindings rather than relying only on a developer's shell state.

## Session recovery and logout

An `aws login` session is temporary and can last up to 12 hours. Reauthenticate when the CLI reports an expired session.

```bash
aws login --profile d3
```

Delete the cached session when finishing work on a shared computer or when switching identities.

```bash
aws logout --profile d3
```

## Troubleshooting

| Symptom | Action |
|---|---|
| `Your session has expired` | Run `aws login --profile d3` again. |
| `AccessDenied` during `aws login` | Ask the administrator to grant `SignInLocalDevelopmentAccess`; do not create an Access Key as an unreviewed workaround. |
| STS returns another account | Run `aws logout --profile d3`, close or separate the old browser session, and log in with the assigned IAM URL. |
| `You must specify a region` | Re-run the two `aws configure set` commands from the one-time setup. |
| A service command returns `AccessDenied` | Record the command, action, resource, and request ID for the IAM-boundary review. Authentication success does not imply service authorization. |

## Credential boundary

- Enter passwords, MFA codes, and browser challenges only in AWS-controlled sign-in surfaces.
- Keep AWS passwords, cached sessions, Access Keys, `.aws` files, and generated credentials outside the repository and team chat.
- Use the named `d3` profile for developer inspection and approved manual operations.
- Use a least-privilege GitHub OIDC role for CI/CD. A developer profile is never a deployment secret.

Official references:

- [AWS CLI login with console credentials](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sign-in.html)
- [AWS CLI authentication options](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-authentication.html)
- [IAM user console sign-in](https://docs.aws.amazon.com/signin/latest/userguide/introduction-to-iam-user-sign-in-tutorial.html)
