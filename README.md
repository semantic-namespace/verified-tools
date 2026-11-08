# Verified local tools: the vocabulary, checked

The first step of a proof of concept: an agent on the user's machine calls
only tools that modules declare, and every declaration is checked against
a shared vocabulary before anything runs. Nothing runs here yet. This
checks declarations.

- `vocab/core.edn`: the core vocabulary, as atlas registry data:
  - the terms allowed in the reserved namespaces (`:data/*`, `:effect/*`,
    `:egress/*`);
  - the refinements (`:data/receipt` always carries `:data/financial`);
  - the policies, written in core terms only.
- `packs/src/verified/pack.clj`: a meta-source pack.
  - It reads each `modules/*/module.edn` as data. Each tool becomes an
    entity whose aspects are exactly what it declares.
  - It derives one rule per refinement.
  - It checks the grammar the rule language can't say: one effect, at least
    one data class, and only core terms or refinements in reserved
    namespaces, suggesting the nearest term.
- `modules/gmail-receipts`: a module that follows the vocabulary. It passes.
- `modules/sloppy-notes`: a module with one mistake per check. Each is
  caught.

```sh
bb check
```

Refinements are checked, never inferred (option A). A policy on
`:data/financial` covers a receipts tool because the refinement rule
guarantees the tool also declares `:data/financial`. Without that rule, a
tool declaring only `:data/receipt` would slip past the policy silently.
