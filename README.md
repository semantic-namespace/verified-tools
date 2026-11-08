# Verified local tools: a proof of concept

An agent on the user's machine gets no terminal. It calls only tools that
modules declare. Each declaration is checked against a shared vocabulary
(`vocab/core.edn`), and each module's code against its declaration. What
passes is compiled into a static artifact. A small router serves only
that, looks every call up in it, and runs each tool in a sandbox generated
from the same declaration.

```sh
bb check    # meta-source over the vocabulary, the modules and their code
bb build    # artifact/artifact.edn: the tools that may run, and why the rest are out
bb serve    # the router: an MCP server over stdio
```

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
  - `router.clj` is the MCP server. For each call it checks that the tool
    is in the artifact, that its arguments are the declared ones with the
    declared types, the caps, the session taint and the user's approvals.
    It then keeps only the declared keys, masks the patterns, and logs to
    `audit/`;
  - `runner.clj` runs one tool inside its bubblewrap sandbox.
- **`approvals.edn`**: the tools the user approved in advance, which is
  the consent a `:confirm` tool needs.

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
- **The agent:** a headless Claude with only the router's tools.
  - It summarised October's receipts correctly.
  - It couldn't email them out or find a password, since no tool does either.
  - It ignored instructions planted in a receipt.
  - Asked to share the summary through a link, it tried, and the router
    refused: once financial data has been read in a session, internet
    egress is blocked. The agent explained the refusal, and nothing was
    uploaded.

## Not done

- **Gmail runs in replay:** recorded responses, keyed by declared route.
  The live egress proxy's allowlist is generated, but not run.
- **Java interop** (`System/getProperty`) isn't flagged by the code check.
  The sandbox covers it.
- **No channel to ask the user mid-session:** consent is `approvals.edn`,
  given in advance.
