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
bb add <git-url> <sha> [--dry-run|--yes]   # install a module: see what it may do first
bb remove <module-dir>                     # uninstall one: see what goes first
bb diff old.edn new.edn                    # what changes between two artifacts
bb test            # all of the below, end to end, in seconds
```

The artifact is built on the user's machine and isn't in git. It names no
machine path: the sandbox commands hold `{root}`, `{bb}` and
`{artifact-dir}`, which the router fills in when it runs a tool. So two
artifacts differ only in what they allow.

## Labels from the boundaries

What a module says it reads, does and reaches is checked against what it
can reach. It is never simply believed:

- **The provider registry.** The vocabulary lists the hosts that already hold
  your data, and which data: `:provider/gmail` holds `:data/email`. A host in
  the registry is `:egress/provider`. Any other host is `:egress/internet`,
  whatever the module calls it.
- **Egress.** A tool's declared egress must reach at least as far as every
  host its module reaches. Calling `evil.example` a provider is an error.
- **Data.** A tool that can reach a provider must declare reading what the
  provider holds.
- **Effects.** A module with only GET routes reads. Any other method writes,
  to a provider, or sends, anywhere else. A tool can't declare less.
- **Roots.** You can say what a folder holds when you bind it:
  `{:notes {:path "/home/me/vault" :holds :data/secret}}`. A tool that
  doesn't declare reading that class won't run on the folder, so no policy
  goes unchecked.

The review that `bb add` shows marks each host as "a registered provider of
…" or "not a registered provider: the internet".

**Where data came from.** The session records the origin of everything it
reads: the hosts a tool reached, or the folders it read. A tool that sends
without asking may send only back to where the session's data came from.
For example, after your notes have been read, the Gmail search is refused,
because its query could carry them to Google. A tool that asks you before
every call is your decision, with the payload in front of you, and the
blocks still apply. The origin is tracked per session and per class, not
per value.

The registry is per host. `www.googleapis.com` serves many APIs, so it would
need host and path prefix.

## Files: roots you bind

A module names a root and what it holds, never where it is:

```clojure
:files {:notes {:holds :data/note :extensions #{"md" "txt" "org"} :max-bytes 500000}}
```

You bind the root to a folder, per module, in `live.edn`:

```clojure
{:roots {:mod/local-notes {:notes "/home/me/notes"}}}
```

- **The sandbox mounts that folder only**, read-only, at `/roots/notes`.
  Nothing else of your files is there, so a symlink out of the folder
  leads nowhere.
- **The SDK reads files from it.** `files/list` and `files/read` accept
  only the declared extensions, relative paths without `..`, and sizes
  under the cap.
- **An unbound root** refuses the call, and the refusal says what to add
  to `live.edn`.
- **meta-source checks** that a root holds a vocabulary data term, and that
  a tool whose code reads a root declares reading what it holds.
- **`bb add` shows a module's roots and flags changes to them:** a new root,
  or more kinds of file read from one, is ▲.

## What can my AI see? (`ai-exposure`)

This module reads your agents' MCP configuration from Claude Code's
`~/.claude.json`, Claude Desktop and Cursor, bound as single-file roots. All
three are optional, because not everyone uses each agent.

- **`agent_connectors`** returns each server's agent, scope, name and kind
  (local program, remote service, or verified tool set), with its host or
  program.
- **`exposure_findings`** flags three things:
  - local programs running as you, which reach everything on their own;
  - an agent whose servers of known reach together reach private data,
    untrusted content and a way out (the trifecta);
  - hosts the bundled registry doesn't know. The registry is small and
    hand-written, a stand-in for a maintained one.

The files hold keys (`:data/secret`). What leaves the tool is
`:data/agent-config`, through a **declassification** the vocabulary lists:

- the tool declares `:yields`;
- every returned key has a type the router checks (a host, a name, one of a
  set, never free text);
- the router masks key- and token-shaped values;
- the session's taint and origins follow what the tool yields, not the
  secrets it read.

Run on a real machine, it found 21 servers in 6 scopes, and nothing of their
headers, env or arguments came out.

## A policy for every Claude Code session: `bb claude` and the hook

```sh
bb claude --profile coding [--folder dir] [--dry-run] [-- claude args]
```

The launcher asks the router for `ai-exposure`'s findings in the folder it
starts in, so it never reads a config itself. It combines them with a profile
(`profiles/*.edn`) and writes the session's settings:

- **MCP servers:** it denies those the findings name as unverified programs
  there, unless the profile keeps them. With `:verified-only`, every server
  but the verified tools is off.
- **Files:** it denies reading files that hold keys.
- **The shell sandbox:** on, with reads of the home folder off except the
  working directory, and no escapes. The network allowlist comes from the
  providers holding what the profile reads, plus the profile's own hosts.
- **The hook:** `bb policy-hook`, the verified-tools policy, runs as a
  PreToolUse hook on every call, built-in tools included:
  - reading a file that holds keys taints the session `:secret`, and any
    later call that reaches the network is denied;
  - a web fetch taints it `:untrusted`, and network commands are then asked
    about;
  - every decision is recorded with its reason.

In a live headless session, the hook denied a shell command reaching
`exfil.invalid` after the session had read a credentials file.

What it can't do yet:

- **A profile that requires the shell sandbox won't start without it.** On
  Linux, Claude Code needs bubblewrap and `socat`.
- **Servers turned off still load.** Denying a server stops its calls, but its
  tool definitions are still loaded. With many servers, a session can exceed
  the context limit: one did here, at about 1.3M tokens. `:verified-only`
  profiles avoid it, with `--strict-mcp-config`.
- **The hook's taint is its own.** It isn't yet shared with the router's
  session taint for verified tools.

## Installing a module

```
$ bb add ../share-snippet 6e6b0a13da26… --dry-run

:mod/share-snippet 0.2.0 from …/share-snippet @ 6e6b0a13da26

share-snippet: Posts a short text to a paste service and returns its link
  from     :mod/share-snippet 0.2.0, …/share-snippet @ 6e6b0a13da26
  takes    text (string)
  reads    note (the user's own notes)
  does     send (sends something on the user's behalf)
  network  internet (anywhere else)
             POST mirror.example/s
             POST paste.example/api/snippets
  returns  url
  at most  1 rows a call, 50 a session
  asks you before every call

Against what is installed (2 tools):
  ▲ share-snippet: new route POST mirror.example/s
  ▲ share-snippet: per-session cap 5 -> 50
```

How `bb add` works:

- **Pinned.** A module is fetched at a full commit SHA, never a branch.
- **Staged.** It's checked together with the vocabulary and every installed
  module in a copy of the project. The copy is built by a `bb` process of
  its own, running inside it, so nothing of the real project changes yet.
- **What you read.** The review is written from the staged artifact, in
  the vocabulary's own words: what the module may do, not what its README
  says it does.
- **Escalations.** Each change against what's installed is marked ▲ when
  it lets a tool reach further. Rankings come from `:vocabulary/rank`:
  more data read, a stronger effect, a wider egress, a new host, route or
  credential scope, more keys returned, higher caps, an extra argument, or
  no longer asking the user.
- **Refused.** A module meta-source finds errors in can't be installed, and
  the review says why. The same goes for a tool the reader didn't find as a
  tool (for example, `:tools` written as `#:tool{…}`), because no policy has
  checked it.
- **Installed on yes.** The module is copied into `modules/`, its URL and
  SHA are recorded in `modules.lock.edn`, and the artifact is rebuilt. The
  artifact's `:modules` then says where each tool came from.

## Real Gmail

Credentials are granted once with your own OAuth client. No third party
holds them.

1. In your own Google Cloud project, enable the Gmail API and create an
   OAuth client of type *Desktop app*. While the app is in Testing, add
   yourself as a test user.
2. Put the client in `secrets.edn`, which isn't in git:

   ```clojure
   {:google {:client-id "…apps.googleusercontent.com" :client-secret "…"}}
   ```
3. Run `bb auth google`. It asks for exactly the scopes the installed tools
   declare (today `gmail.readonly`) on Google's consent page, receives the
   answer on a local port (PKCE, a fresh state), and writes the refresh
   token to `secrets.edn`, readable by you only.

From then on, the router turns the refresh token into access tokens as
needed:

- it keeps each one until a minute before it expires;
- it renews once after a 401;
- it refuses a token missing a declared scope, saying to run `bb auth`
  again.

A tool never holds a token. While the OAuth app is in Testing, Google
expires refresh tokens after 7 days, so run `bb auth google` again then. A
pasted `:access-token` still works, for an hour.

The receipts query is declared in `modules/gmail-receipts/module.edn`.

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
    arguments, no calls outside the SDK, no Java interop (beyond
    `java.lang.Math`), none of the SDK's insides (its dynamic vars are the
    runner's to bind), only declared result keys.
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
| `local-notes` | notes in a folder you bind, searched locally with no network; only path, title and a few lines leave the tool | yes |
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

- **Real Gmail** hasn't been run yet; see below.
- **Taint is per session.** In one live run, the agent itself suggested
  pasting the summary into a fresh session. That is the user's own choice
  to make, and it still goes through the confirmation, but the policy
  doesn't follow data the user carries by hand.
- **Interactive elicitation** was tested at the protocol level, with the
  driver playing the client. Claude Code's dialog itself hasn't been seen.
