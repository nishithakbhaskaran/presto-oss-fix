# RFC-0000 for Presto

## ANSI SQL `TRIM` syntax without new reserved keywords

Proposers

* Nishitha K Bhaskaran

## Related Issues

* prestodb/presto#28190 — `feat(parser): Add support for ANSI SQL syntax in trim function` (merged, then reverted)
* prestodb/presto#28334 — `revert(parser): Remove support for ANSI SQL syntax in trim function` (the revert this RFC responds to)
* Upstream reference: trinodb/trino commit `a319b0b6edd9a0d5295af29b29d00aa9d88850e0` (Yuya Ebihara, 2022-02-28)

## Summary

Add ANSI SQL `TRIM(<specification> <char> FROM <source>)` syntax to Presto under three
hard invariants:

1. **No new reserved keywords.** `TRIM`, `BOTH`, `LEADING`, and `TRAILING` are all added to
   the grammar's `nonReserved` rule, so they remain usable as identifiers.
2. **Strictly additive grammar.** The new alternative can only match token sequences that
   are a *syntax error today*. No currently-valid query changes its parse tree.
3. **No new AST node.** The new syntax is desugared in `AstBuilder` into the existing
   `trim` / `ltrim` / `rtrim` `FunctionCall` nodes, exactly as Presto already does for
   `SUBSTRING(... FROM ...)` and `POSITION(... IN ...)`. Nothing downstream of the parser
   changes.

Net footprint: **4 files** (grammar, `AstBuilder`, docs, tests) versus 16 files in #28190.

## Background

Presto today supports only the function-call spelling of trim:

```sql
SELECT trim(s);          -- both ends, whitespace
SELECT trim(s, chars);   -- both ends, given characters
SELECT ltrim(s[, chars]);
SELECT rtrim(s[, chars]);
```

The ANSI spelling `TRIM(LEADING '.' FROM s)` is a syntax error. This is a real portability
gap: the syntax is in SQL:1992, and Trino, PostgreSQL, MySQL, Oracle, Snowflake, and
Spark SQL all accept it. Queries migrated to Presto from any of those engines must be
rewritten.

#28190 implemented the syntax and was merged, then reverted by #28334 with the reason:

> This PR makes trim a reserved keyword not acceptable for existing payloads - even if
> sql std. We should not add any new reserved words.

That objection is correct, and it is worth being precise about the two independent
regressions in #28190, because the fix for each is different.

### Regression 1: `TRIM` became a reserved word

#28190 added `TRIM: 'TRIM';` to the lexer and added `BOTH`, `LEADING`, and `TRAILING` to
the `nonReserved` rule — but **omitted `TRIM` itself from `nonReserved`**. In Presto's
grammar, a token is usable as an identifier only if it appears in `nonReserved` (see
`SqlParser.exitNonReserved`, which rewrites those tokens into `IDENTIFIER`). The omission
silently promoted `TRIM` to a reserved word, breaking every payload that used `trim` as a
column, table, alias, or CTE name:

```sql
SELECT trim FROM t;              -- broke
CREATE TABLE t (trim varchar);   -- broke
SELECT x AS trim FROM t;         -- broke
WITH trim AS (SELECT 1) ...      -- broke
```

`BOTH`, `LEADING`, and `TRAILING` were *not* actually reserved by #28190 (contrary to the
revert's release note) — only `TRIM` was.

### Regression 2: the new grammar alternatives shadowed the existing call forms

This is the subtler and more damaging problem, and it is not fixed by the `nonReserved`
entry alone. #28190 added:

```antlr
| TRIM '(' (trimsSpecification? trimChar=valueExpression? FROM)?
    trimSource=valueExpression ')'                                    #trim
| TRIM '(' trimSource=valueExpression ',' trimChar=valueExpression ')' #trim
```

The entire `(... FROM)` prefix is optional in the first alternative, and the second
alternative matches the comma form explicitly. Because these alternatives were placed
*before* `#functionCall` in `primaryExpression`, ANTLR's ordered-alternative resolution
routed **every existing `trim(x)` and `trim(x, y)` in every existing payload** through the
brand-new `#trim` alternative and the new `Trim` AST node, instead of the ordinary
`#functionCall` path they had used for a decade.

Consequences of that re-routing:

* Unqualified `trim(...)` calls that should resolve to a **user-defined `trim` in a
  function namespace** were hard-wired to the built-in.
* Every consumer of the AST had to learn a node it had never seen. `Trim` reached
  `ExpressionAnalyzer`, `ExpressionInterpreter`, `SqlToRowExpressionTranslator`,
  `AggregationAnalyzer`, `ExpressionFormatter`, `AstVisitor`, `DefaultTraversalVisitor`,
  `ExpressionRewriter`, and `ExpressionTreeRewriter`. Any *out-of-tree* `AstVisitor`
  implementation — plugins, `presto-verifier`, custom analyzers, Presto-on-Spark tooling —
  would fall through to `visitExpression` and produce a wrong result or an error for
  ordinary `trim(x)` queries it previously handled fine.
* `trim(x)` was rewritten to a `FunctionCall` only during analysis, so the plan-time and
  formatter paths each needed their own copy of the same rewrite (three copies total).

In other words, #28190 did not merely *add* syntax; it moved the existing syntax onto a new
code path. That is what "not acceptable for existing payloads" means in the broad sense,
and it is the class of problem this RFC is designed to make structurally impossible.

### Goals

* Accept the ANSI `TRIM` forms.
* Zero change to the parse tree, analysis, plan, or execution of any query that is valid on
  Presto today.
* Add zero reserved keywords.
* Keep the change auditable — small enough that a reviewer can verify the compatibility
  claim by reading the diff.

### Non-goals

* Changing the semantics or return types of the existing `trim` / `ltrim` / `rtrim`
  functions. In particular the `char(n)` return-type question (prestodb/presto#28280, also
  reverted in #28325) is **explicitly out of scope**; the desugaring inherits whatever
  behaviour those functions have at the time, and this RFC neither fixes nor worsens it.
* Preserving the original ANSI spelling in `EXPLAIN` / `SHOW CREATE VIEW` output (see
  Trade-offs).
* Supporting `TRIM(FROM source)` (no specification and no trim character). Trino rejects
  this form; so do we.

## Proposed Implementation

### Layering

| Layer | Change |
| --- | --- |
| Lexer (`SqlBase.g4`) | 4 new tokens: `BOTH`, `LEADING`, `TRAILING`, `TRIM` |
| `nonReserved` (`SqlBase.g4`) | all 4 tokens added → none is reserved |
| Grammar rule (`SqlBase.g4`) | 1 new `#trim` alternative + 1 new `trimsSpecification` rule |
| `AstBuilder.java` | `visitTrim` desugars to a `FunctionCall` (~25 lines) |
| Analyzer / planner / interpreter / formatter / AST visitors | **no change** |
| Native execution, Presto-on-Spark, `presto-analyzer`, plugins | **no change** |
| Docs | `functions/string.rst` only — **no change to `language/reserved.rst`** |

### Grammar

Add the alternative alongside the other ANSI special forms, i.e. **after** `#functionCall`
in `primaryExpression`, next to `#substring`:

```antlr
| TRIM '(' (trimsSpecification trimChar=valueExpression? | trimChar=valueExpression) FROM
    trimSource=valueExpression ')'                                                    #trim
| SUBSTRING '(' valueExpression FROM valueExpression (FOR valueExpression)? ')'        #substring
```

and the specification rule:

```antlr
trimsSpecification
    : LEADING
    | TRAILING
    | BOTH
    ;
```

Two properties of this rule carry the entire compatibility argument:

1. **`FROM` is mandatory.** A `FROM` token inside `trim( ... )` is unconditionally a syntax
   error in Presto today. Therefore the new alternative is unreachable for any
   currently-valid input — `trim(x)` and `trim(x, y)` cannot be captured by it.
2. **The alternative is placed after `#functionCall`.** Even for inputs where both could
   in principle apply, ANTLR prefers the earlier alternative, so the ordinary function-call
   path keeps priority. This mirrors how `SUBSTRING` and `POSITION` are already positioned.

Add all four tokens to `nonReserved`:

```antlr
    | BEFORE | BERNOULLI | BOTH | BRANCH
    | LANGUAGE | LAST | LATERAL | LEADING | LEVEL | LIMIT | LOGICAL
    | ... | TO | TRAILING | TRANSACTION | TRIM | TRUNCATE | TRY_CAST | TYPE
```

This is not a novel pattern: `SUBSTRING` and `POSITION` are already non-reserved tokens
that each have a dedicated special-form alternative in `primaryExpression`. `TRIM` joins
them.

### Desugaring in `AstBuilder`

`visitTrim` returns an ordinary `FunctionCall`. No `Trim` AST node is introduced.

```java
@Override
public Node visitTrim(SqlBaseParser.TrimContext context)
{
    String functionName = trimFunctionName(context.trimsSpecification());

    ImmutableList.Builder<Expression> arguments = ImmutableList.builder();
    arguments.add((Expression) visit(context.trimSource));
    if (context.trimChar != null) {
        arguments.add((Expression) visit(context.trimChar));
    }

    return new FunctionCall(getLocation(context), QualifiedName.of(functionName), arguments.build());
}

private static String trimFunctionName(SqlBaseParser.TrimsSpecificationContext specification)
{
    if (specification == null) {
        return "trim";   // TRIM(char FROM source) — BOTH is the ANSI default
    }
    switch (((Token) specification.getChild(0).getPayload()).getType()) {
        case SqlBaseLexer.BOTH:
            return "trim";
        case SqlBaseLexer.LEADING:
            return "ltrim";
        case SqlBaseLexer.TRAILING:
            return "rtrim";
    }
    throw new IllegalArgumentException("Unsupported trim specification: " + specification.getText());
}
```

This is the same shape as the existing `AstBuilder` methods for the other ANSI special
forms, which is the strongest evidence that it is the idiomatic choice for Presto:

```java
// SUBSTRING(x FROM y FOR z) -> substr(x, y, z)
return new FunctionCall(getLocation(context), QualifiedName.of("substr"), ...);
// POSITION(a IN b) -> strpos(b, a)
return new FunctionCall(getLocation(context), QualifiedName.of("strpos"), Lists.reverse(...));
// NORMALIZE(x, NFC) -> normalize(x, 'NFC')
// a || b            -> concat(a, b)
```

`ExpressionFormatter` has no `visitSubstring` or `visitPosition` for exactly this reason:
desugared forms are ordinary function calls by the time anything downstream sees them.

### Syntax mapping

| ANSI syntax | Desugars to | Notes |
| --- | --- | --- |
| `TRIM(BOTH FROM s)` | `trim(s)` | |
| `TRIM(LEADING FROM s)` | `ltrim(s)` | |
| `TRIM(TRAILING FROM s)` | `rtrim(s)` | |
| `TRIM(BOTH c FROM s)` | `trim(s, c)` | argument order reversed |
| `TRIM(LEADING c FROM s)` | `ltrim(s, c)` | |
| `TRIM(TRAILING c FROM s)` | `rtrim(s, c)` | |
| `TRIM(c FROM s)` | `trim(s, c)` | `BOTH` is the ANSI default |
| `TRIM(s)`, `TRIM(s, c)` | *unchanged* | ordinary `#functionCall`, untouched |
| `TRIM(FROM s)` | *syntax error* | rejected by the grammar; Trino parity |
| `TRIM(LEADING)` | *unchanged* | no `FROM`, so an ordinary one-argument call over a column named `leading` |

Because the 2-argument built-ins take `(source, characters)` while ANSI writes
`(characters FROM source)`, `visitTrim` reverses the operand order — the same
transformation `visitPosition` already performs for `POSITION(a IN b)`.

### Prototype validation

The grammar above was generated with ANTLR 4.13.2 (the version in `pom.xml`) from a patched
copy of `SqlBase.g4` and exercised with `PredictionMode.LL_EXACT_AMBIG_DETECTION`. ANTLR
reported **no new generation-time warnings**. Observed parse results:

Existing payloads — all still take their current path:

| Input | Resolves to |
| --- | --- |
| `SELECT trim FROM t` | `columnReference` ✓ |
| `SELECT trim, both, leading, trailing FROM t` | 4 × `columnReference` ✓ |
| `SELECT x AS trim FROM t` | alias ✓ |
| `CREATE TABLE t (trim varchar, both integer)` | column names ✓ |
| `WITH trim AS (SELECT 1) SELECT * FROM trim` | CTE name ✓ |
| `SELECT trim FROM trim GROUP BY trim ORDER BY trim` | ✓ |
| `CREATE VIEW trim AS SELECT trim FROM t` | ✓ |
| `SELECT trim(x) FROM t` | `functionCall{trim}` ✓ |
| `SELECT trim(x, y) FROM t` | `functionCall{trim}` ✓ |
| `SELECT trim(x, y, z) FROM t` | `functionCall{trim}` ✓ |
| `SELECT mycat.mysch.trim(a, b, c) FROM t` | `functionCall{mycat.mysch.trim}` ✓ |
| `SELECT trim(x) OVER (PARTITION BY y) FROM t` | `functionCall` + `over` ✓ |
| `SELECT trim(x) FILTER (WHERE y) FROM t` | `functionCall` + `filter` ✓ |
| `SELECT trim(trim) FROM trim` | `functionCall{trim}` over `columnReference` ✓ |
| `SELECT CAST(trim AS varchar) FROM t` | ✓ |

New syntax:

| Input | Parse |
| --- | --- |
| `SELECT trim(LEADING FROM x)` | `spec=LEADING, char=-, src=x` |
| `SELECT trim(BOTH '$' FROM x)` | `spec=BOTH, char='$', src=x` |
| `SELECT trim(TRAILING 'ER' FROM upper('worker'))` | `spec=TRAILING, char='ER', src=upper(...)` |
| `SELECT trim('!' FROM x)` | `spec=-, char='!', src=x` |
| `SELECT trim(BOTH FROM trim(LEADING '.' FROM x))` | nests correctly |
| `SELECT trim(FROM x)` | syntax error (intended) |

### Known limitation: `trim(leading FROM x)`

Because `LEADING` / `TRAILING` / `BOTH` stay non-reserved, `TRIM(leading FROM x)` is
genuinely ambiguous: `leading` could be the trim specification or a column named `leading`.
ANTLR resolves the optional `trimsSpecification` subrule greedily, so the **keyword reading
wins** — matching Trino, PostgreSQL, and the ANSI grammar.

This breaks nothing, because reaching that ambiguity requires a `FROM` inside `trim(...)`,
which is a syntax error today. There is no existing query it can affect. Users who want the
column reading have a standard escape hatch, verified in the prototype:

```sql
SELECT TRIM("leading" FROM x)   -- quoted identifier: column, not specification
```

This will be documented in `functions/string.rst`.

## Other Approaches Considered

**A. Re-land #28190 with `TRIM` added to `nonReserved` (minimal fix).** Fixes Regression 1
only. Existing `trim(x)` / `trim(x, y)` calls would still be re-routed onto the new `Trim`
AST node, so out-of-tree `AstVisitor` implementations still break and function-namespace
resolution of a user's own `trim` is still affected. Rejected: it addresses the stated
revert reason but not the underlying compatibility problem.

**B. Keep a `Trim` AST node (Trino parity), with an additive grammar and full
`nonReserved` coverage.** Fully correct on the reserved-word axis, and preserves the
original spelling through `ExpressionFormatter`. Rejected because the node still has to be
taught to ~10 in-tree visitors and every out-of-tree one, for a purely cosmetic benefit.
The AST is a de-facto plugin API in Presto; adding a node to it is a compatibility event
that this feature does not need. Presto's own precedent (`SUBSTRING`, `POSITION`,
`NORMALIZE`, `||`) is to desugar.

**C. Gate the syntax behind a session property / feature flag.** Rejected: with a strictly
additive grammar there is no behaviour to gate — the new syntax is unreachable for existing
queries by construction — so a flag adds configuration surface and a second code path
without reducing risk. Worth revisiting only if review disagrees with the additivity
argument.

**D. Rewrite in a client or an optional pre-parse pass.** Rejected: pushes the burden onto
every client and does not make Presto ANSI-conformant.

### Trade-offs of the chosen approach

* **Formatter round-trip is lossy.** `TRIM(LEADING FROM x)` re-renders as `ltrim(x)` in
  `EXPLAIN`, `SHOW CREATE VIEW`, and anywhere `ExpressionFormatter` is used. Views created
  with the ANSI spelling are stored desugared.

  We consider this a net positive: a view created on a coordinator with this change remains
  parseable by a coordinator without it, so the feature does not create forward-only view
  metadata. It also matches what `SUBSTRING`/`POSITION` already do, so it is not a new
  inconsistency in Presto's output.
* **No dedicated node for tools that want the original syntax.** No in-tree consumer needs
  it. If one appears later, the node can be added then, as a separate and separately
  reviewable change.

## Adoption Plan

* **Impact on existing users:** none. The change is additive by construction: every new
  grammar path requires a `FROM` inside `trim(...)`, which is a syntax error today. No new
  reserved words, so no identifier stops working. No session properties or configuration
  are added.
* **Phasing out older behaviour:** nothing is phased out. The function-call forms
  `trim(s)`, `trim(s, c)`, `ltrim`, and `rtrim` remain fully supported and take exactly the
  same code path they take today. No migration tooling is needed.
* **Native / Presto C++ and Presto-on-Spark:** no work required. Desugaring happens in the
  parser, so both see the same `trim` / `ltrim` / `rtrim` calls they already support. This
  is a concrete advantage over #28190, which had to modify `SqlToRowExpressionTranslator`
  because the `Trim` node leaked into the planner.
* **Documentation:** add the ANSI forms to `presto-docs/src/main/sphinx/functions/string.rst`
  next to the existing `trim` entries, including the quoted-identifier escape hatch and a
  note that the ANSI forms are shorthand for the existing functions.
  **`presto-docs/src/main/sphinx/language/reserved.rst` is deliberately left unchanged** —
  no keyword is being reserved. A reviewer can use "does this PR touch `reserved.rst`?" as
  a one-line check of the central claim.
* **Machine-checked, not just asserted.** Presto already has a validator for exactly this
  invariant: `ReservedIdentifiers.validateDocs`, bound to the `validate` phase of
  `presto-docs` via `exec-maven-plugin`. It derives the reserved set *empirically* — for
  every keyword in `SqlBaseLexer.VOCABULARY` it attempts `PARSER.createExpression(name)` and
  treats the keyword as reserved if the result is not an `Identifier` — then fails the build
  if that set does not match `reserved.rst` exactly, in either direction. So if this change
  reserved `TRIM`, `mvn validate -pl presto-docs` would fail with
  `Reserved identifier is not documented: TRIM`. With the change applied and `reserved.rst`
  untouched it reports `Validated 68 reserved identifiers`, unchanged from master. The
  no-new-reserved-words claim is therefore enforced by CI rather than by review.
* **Release note:**
  ```
  General Changes
  * Add support for the ANSI SQL ``TRIM`` syntax, for example
    ``TRIM(LEADING '.' FROM x)``. No new reserved keywords are introduced and existing
    ``trim``, ``ltrim``, and ``rtrim`` calls are unaffected.
  ```
* **Out of scope for future work:** the `char(n)` return type of `trim` / `ltrim` / `rtrim`
  (prestodb/presto#28280, reverted in #28325); ANSI `OVERLAY`; and a `Trim` AST node for
  tools that need syntax fidelity in `SHOW CREATE VIEW`.

## Test Plan

**Compatibility regression guard — the core of the plan.** In
`presto-parser/.../TestSqlParser.java`, extend `testNonReserved` (and add a focused
`testTrimKeywordsAreNotReserved`) to assert that each of `TRIM`, `BOTH`, `LEADING`, and
`TRAILING` still parses as an `Identifier` in the positions that #28190 broke:

```java
assertStatement("SELECT trim, both, leading, trailing FROM t", ...4 Identifiers...);
assertStatement("SELECT x AS trim FROM t", ...);
assertStatement("CREATE TABLE t (trim varchar)", ...);
assertStatement("WITH trim AS (SELECT 1) SELECT * FROM trim", ...);
assertExpression("trim", new Identifier("trim"));
```

**Existing-call-form invariance.** Assert the *exact* AST for the current spellings, so any
future regrammaring that re-routes them fails the build:

```java
assertExpression("trim(x)",    new FunctionCall(QualifiedName.of("trim"),  ImmutableList.of(identifier("x"))));
assertExpression("trim(x, y)", new FunctionCall(QualifiedName.of("trim"),  ImmutableList.of(identifier("x"), identifier("y"))));
assertExpression("mycat.mysch.trim(a, b, c)", /* qualified FunctionCall */);
```

**New syntax → desugared AST.** One `assertExpression` per row of the syntax-mapping table,
asserting the resulting `FunctionCall` name and argument order, including the reversal for
`TRIM(c FROM s)` → `trim(s, c)`.

**Negative cases.** `assertInvalidExpression` for `trim(FROM x)`, `trim(BOTH x)` (no
`FROM`), and `trim(BOTH '.' FROM)`. Note that `trim(LEADING)` is *not* an error — with no
`FROM` it is an ordinary one-argument call over a column named `leading`, which is itself
a useful demonstration of additivity and is asserted as such.

**Reserved-word invariant.** `mvn validate -pl presto-docs` runs
`ReservedIdentifiers.validateDocs`, which fails the build if any keyword became reserved
without being documented in `reserved.rst`. This is the authoritative check.

**Escape hatch.** `assertExpression("trim(\"leading\" FROM x)", ...)` resolves `"leading"`
to the trim character, not the specification.

**Semantics.** In `presto-main-base/.../TestStringFunctions.java`, restore the #28190
assertions for every form: whitespace defaults, custom characters, multi-character sets,
non-Latin/Unicode input, `char(n)` and `varchar(n)` inputs, and `NULL` handling — each
asserted equal to the corresponding `trim`/`ltrim`/`rtrim` call to pin the equivalence.

**Nesting and interaction.** `trim(BOTH FROM trim(LEADING '.' FROM x))`; the new form
inside `GROUP BY`, `ORDER BY`, `HAVING`, a window frame, and a lambda body; and a column
literally named `trim` used as the trim source (`trim(LEADING FROM trim)`).

**End-to-end.** Add cases to `presto-tests` (`AbstractTestQueries`) so the forms are
executed, not just parsed, and are covered by the native (Presto C++) and Presto-on-Spark
test suites that inherit from it — confirming the no-downstream-change claim empirically.

**Formatter.** A test documenting the intended lossy round-trip:
`formatExpression(parse("TRIM(LEADING FROM x)"))` → `ltrim(x)`, mirroring the existing
`SUBSTRING`/`POSITION` behaviour.
