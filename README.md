# Phalanx

Phalanx is a small Java app that lets an agent sign in to a website without ever seeing the password.

The agent can ask. A person still has to click Allow. The password stays with Phalanx and is typed into the page by a Chrome extension. The agent only hears whether the sign-in worked.

This is a learning project, not a product. It exists so you can watch that handoff happen on your own machine.

## The problem

An agent that uses a browser eventually hits a login form. The easy shortcut is to put the password in the prompt, the code, or an environment variable. Then the password is in the model’s context, in logs, and in whatever system is running the agent.

Phalanx keeps that password in one file the agent is not allowed to read. When a sign-in is needed, Phalanx checks who is asking, asks you, and fills the form itself.

## What happens when you run it

1. The stand-in agent asks Keycloak for a JWT. That token says “this caller has the role `agent`.” It does not contain the website password.
2. The agent opens a local login page in Chromium and sends Phalanx three things: the site, a short reason, and the token.
3. The broker checks the token. The signature has to be Keycloak’s. The issuer, audience, and expiry have to match. The role has to be `agent`. The site in the request has to be the site saved with the login. If any of that fails, the answer is `denied` and the Allow dialog never appears.
4. If the checks pass, a window asks you to Allow or Deny. Deny ends the request.
5. If you allow it, the broker sends the username and password to the Chrome extension over a local channel. The extension types them into the form and submits it. While that happens, the agent is waiting on the HTTP call and is not reading the page.
6. The agent gets a status: `success`, `denied`, `url_mismatch`, or `fill_failed`. On success it can read the heading “Signed in as ada”. It never gets the password.

```text
agent  ->  Keycloak     get a JWT
agent  ->  broker       site, reason, JWT
broker ->  you          Allow or Deny
broker ->  extension    username and password
extension              fills the form and submits
broker ->  agent        status only
```

## The pieces

**Broker** (`java -jar target/phalanx.jar broker`). This is Phalanx. It reads `login.properties`, checks the token with Nimbus JOSE+JWT, shows the Swing dialog, and serves the login page at `http://localhost:4711/login.html`.

**Agent** (`java -jar target/phalanx.jar agent`). This is not the product. It is a small Java program that stands in for a real agent so you can watch the call. Playwright opens the browser. A real agent would call the same `/signin` endpoint and would still only receive a status.

**Extension** (`extension/`). Chrome will not run Java inside an extension, so this part is a short script. It receives the fill, checks the page URL, types into the form, and reports back.

**Native host.** Chrome starts `~/Library/Application Support/Phalanx/phalanx-host.sh` when the extension connects. That script runs the Java `host` command, which forwards messages between Chrome and the broker on `127.0.0.1:4712`. The password crosses that local pipe. It does not go to the agent.

**Keycloak.** The identity provider, running in Docker. It issues the JWT. The realm, the client, and the `agent` role are in `keycloak/phalanx-realm.json`.

## What the token is allowed to do

The broker treats the token as proof of who is asking, not as permission to skip you.

- **JWT.** Signed by Keycloak. The broker checks `iss`, `aud` (`phalanx-broker`), `exp`, and the signature against Keycloak’s public keys.
- **RBAC.** The token’s realm roles must include `agent`. Any other role is denied before the dialog.
- **ABAC.** The site on the request must match the site stored with the login. A different site is denied before the dialog.
- **You.** Allow is still required after both checks.

## Demo logins

These are local learning passwords, not real accounts.

| Who | Username | Password | Used for |
| --- | --- | --- | --- |
| Website | `ada` | `correct-horse` | The login in `login.properties`. The extension types this into the page. |
| Keycloak user | `agent` | `agent` | How the stand-in agent gets a JWT. |
| Keycloak admin | `admin` | `admin` | The Keycloak admin console. The sign-in flow does not use it. |

## Run it

You need Java 21, Maven, and Docker. The first agent run downloads Playwright’s Chromium.

From this folder:

```bash
docker compose -f keycloak/docker-compose.yml up
```

Wait until Keycloak is up, then in a second terminal:

```bash
mvn -q package
java -jar target/phalanx.jar broker
```

In a third terminal:

```bash
java -jar target/phalanx.jar agent
```

Click **Allow**. The browser should land on “Signed in as ada”, and the agent terminal should print `status: success`. Press Enter to close the browser.

The broker has to be started from this folder, because it reads `login.properties` from the current directory and the extension from `extension/`.

## Try a denial

The role and the site are checked before the dialog. Point a request at any site other than `http://localhost:4711/login.html` and the broker answers `denied` without asking you.

## What this does not do

There is no test suite, no audit log, no Touch ID, and no sync. One login, one role, one site rule. Passkeys and “Sign in with Google” are not part of it. After a successful sign-in, the agent is inside the website. Phalanx’s job ended when the password was filled.
