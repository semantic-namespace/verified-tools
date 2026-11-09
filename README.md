# Verified local tools: a proof of concept

An agent on the user's machine gets no terminal. It calls only tools that
modules declare. Each declaration is checked against a shared vocabulary
(`vocab/core.edn`), and each module's code against its declaration. What
passes is compiled into a static artifact. A small router serves only
that, looks every call up in it, and runs each tool in a sandbox generated
from the same declaration.

```sh
bb check           # meta-source over the vocabulary, the modules and their code
bb build [live]    # artifact/artifact.edn: the tools that may run, and why the rest are out
bb serve           # the router: an MCP server over stdio (--artifact --live --audit --confirm)
bb confirm         # the user, in their own terminal: what the router is asking, and the answer
bb fake-provider   # a stand-in for Gmail and the paste service, for the live path
bb test            # all of the below, end to end, in seconds
```

The artifact is built on the user's machine and isn't in git. Its sandbox
commands name absolute paths.

## Tests (`test/`)

- **`check_test`**: the good modules and the vocabulary are clean, and each
  mistake planted in the bad modules is found by its own check.
- **`build_test`**: only runnable modules get in, and each excluded one
  lists its reasons. Every sandbox has no network and binds only its own
  module. The egress allowlists are exactly the declared routes, and the
  masking patterns come in order.
- **`router_test`**, in replay:
  - the refusals: arguments, types, length, unknown and excluded tools;
  - taint blocks before anyone is asked;
  - asking by elicitation: yes, unticked, declined, cancelled;
  - asking in the terminal: yes, no, and no answer;
  - the per-session cap;
  - masking.
- **`live_test`**, against the fake provider run in-process:
  - the router adds the token, and nothing goes out without one;
  - the paste service receives exactly the allowed text, and no Gmail
    token;
  - eight forged requests are refused.

Writing them found a real leak. When the card-number pattern ran first,
it matched the digits inside an IBAN and left the IBAN's country code and
last digits readable. The patterns are now an ordered list, most specific
first.

## The parts

- **`vocab/core.edn`**, as atlas registry data:
  - the core terms in the reserved namespaces (`:data/*`, `:effect/*`, `:egress/*`);
  - the refinements (`:data/receipt` always carries `:data/financial`: checked, never inferred);
  - the policies, written in core terms;
  - the session policy: what's blocked after a data class has been read, and what's masked.
- **`sdk/`**, the only way a module reaches the world:
  - `http/call` takes a declared route, by literal keyword;
  - `query/fill` takes a declared template, with typed holes;
  - `files/read` takes a declared root;
  - `out/rows` returns only the declared keys;
  - `parse/*` is pure.
- **`packs/src/verified/pack.clj`**, a meta-source pack. It reads each
  `module.edn` as data and each module's code through clj-kondo's
  analysis, then checks:
  - the vocabulary's grammar;
  - the rules derived from the refinements;
  - code against declaration: tool functions, literal and declared SDK
    arguments, no calls outside the SDK, only declared result keys.
- **`router/`**:
  - `build.clj` writes the artifact, with its SHA-256;
  - `router.clj` is the MCP server. For each call it checks:
    - that the tool is in the artifact;
    - that its arguments are the declared ones, with the declared types;
    - the caps;
    - the session taint;
    - for a tool that must ask, the user's answer.

    It then keeps only the declared keys, masks the patterns, and logs to
    `audit/`. It is also the tools' only way out (see below);
  - `runner.clj` runs one tool inside its bubblewrap sandbox;
  - `confirm.clj` is `bb confirm`.
- **`live.edn`** is the user's, and isn't in git. It says where hosts
  resolve and where credentials are, and how to ask:
  `dev/live.fake.edn` points everything at the fake provider.

### Egress: the router is the proxy

A sandbox never has a network. Live, the SDK's transport writes each
request to the tool's stdout. The router makes the request only when its
host, method and path match a route the module declares, and adds the
credential that route names. The answer goes back on the tool's stdin.

The token stays in the router, outside the sandbox. The check sits in the
router, not in the SDK, so a module that skipped the SDK and wrote a
request by hand gets the same answer.

### Asking the user, around the agent

A tool with `:confirm true` runs only after the user allows that one
call. The router builds the question from the artifact and the exact
arguments, never from the agent's words.

It asks by MCP elicitation when the client offers it. Otherwise, or when
`live.edn` says `:confirm {:via :terminal}`, it asks in a file that the
user answers with `bb confirm <id> yes|no` in their own terminal.

No answer, a cancel or a timeout is a no. A tool blocked by the session's
taint is refused before anyone is asked: the user can't approve their way
past `:session/blocks` from inside the session.

## The modules

| Module | Purpose | In the artifact? |
|---|---|---|
| `gmail-receipts` | receipts from Gmail, read-only, parsed locally; only receipt rows leave the tool | yes |
| `share-snippet` | posts a text to a paste service: fine alone, a leak after the session read something private | yes |
| `leaky-receipts` | a clean declaration and off-pattern code: a computed route, an HTTP post, a shell call, an undeclared key | no: 4 reasons |
| `sloppy-notes` | one declaration mistake per check, and no code | no: 7 reasons |

## What the PoC shows

- **Declarations and code are checked before anything runs.** Each
  mistake is caught by its own check, and the artifact says why each
  excluded module is out.
- **The router decides by lookup.** A wrong argument is refused, and so is
  a tool outside the artifact. Every call is logged against the artifact's
  version.
- **The sandbox:** under the receipts tool's own profile, `~/.ssh` doesn't
  exist, there's no network, and other modules' code is invisible.
- **Live egress through the router**, against the fake provider:
  - the receipts tool, without a network, got its five Gmail calls made
    by the router, with the bearer token added outside the sandbox;
  - the provider logged the query, `label:receipts after:2026/10/01 before:2026/10/31`;
  - forged requests at the router were each refused: another host, an
    undeclared path, `../` in a hole, a POST or DELETE on a GET route,
    and another module's route.
- **Asking**, by elicitation and by terminal, with yes and with no: the
  paste service received exactly the confirmed text, or nothing.
- **The agent:** a headless Claude (Claude Code 2.1.319) with only the
  router's tools.

  Replay runs:
  - It summarised October's receipts correctly.
  - It couldn't email them out or find a password, since no tool does either.
  - It ignored instructions planted in a receipt.
  - Asked to share the summary through a link, it tried, and the router
    refused: once financial data has been read in a session, internet
    egress is blocked. The agent explained the refusal, and nothing was
    uploaded.

  Live runs:
  - It shared a note. Claude Code offers elicitation, and with no human
    in `-p` mode it answers *cancel*. The router took that as a no:
    nothing was posted, and the agent said so.
  - With `:via :terminal`, the user approved the exact text with
    `bb confirm`, and the link came back.
  - It summarised the receipts, 269.19 EUR, and then tried to share them.
    `:session/blocks` refused it before any question, and nothing was
    posted.

## Not done

- **Real Gmail** needs a `gmail.readonly` access token in a file named by
  `live.edn`, for example from the OAuth playground. The path is the one
  run against the fake provider; it hasn't been run against Google.
- **Java interop** (`System/getProperty`) isn't flagged by the code check.
  The sandbox covers it.
- **Taint is per session.** In one live run, the agent itself suggested
  pasting the summary into a fresh session. That is the user's own choice
  to make, and it still goes through the confirmation, but the policy
  doesn't follow data the user carries by hand.
- **Interactive elicitation** was tested at the protocol level, with the
  driver playing the client. Claude Code's dialog itself hasn't been seen.
