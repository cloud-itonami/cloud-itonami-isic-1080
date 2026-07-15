# cloud-itonami-isic-1080: Prepared Animal Feeds Manufacturing Coordination Actor

**ISIC Rev. 5 1080** — Manufacture of Prepared Animal Feeds

A distributed actor for autonomous, compliant coordination of prepared-animal-feed plant operations: raw-material intake (grain, protein meal, vitamin/mineral premix, medication where applicable) → mixing (batching to a homogeneous mash) → pelletizing (steam conditioning + die extrusion) → cooling (counter-flow cooler down to near-ambient) → packaging (bagging/bulk loading) → metal-detector and packaging-integrity inspection → compliance audit → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Mixing-line, pelletizing-line, and cooling/packaging-line operation and food-safety certification authority remain exclusive to licensed feed-mill staff and regulators.

## Scope

This actor coordinates **plant-operations workflow** for prepared-animal-feed manufacturing (poultry, swine, dairy-cattle, and aquaculture pelleted rations):

- Production batch logging (mixing/pelletizing batch, nutrient-content data logging)
- Equipment maintenance scheduling (mixers, steam conditioners, pellet mills, coolers, bagging/loading lines)
- Food-safety concern escalation (mycotoxin/aflatoxin contamination, medicated-feed cross-contact, moisture/spoilage risk)
- Finished-product shipment coordination

**Out of scope:**
- Direct mixing-line/pelletizing-line/cooling-line/bagging-line equipment control (plant staff exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch mixing-line/pelletizing-line/cooling-line/bagging-line control or food-safety certification
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Plant/batch record not independently verified/registered before any proposal is made against it (`:batch-not-registered`) — applies to every proposal op, not only shipment coordination
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Steam-conditioning temperature below the product's minimum required temperature (`:conditioning-temp-below-minimum`)
  - Post-pellet cooling time exceeds the product's maximum window (`:cooling-time-exceeds-max`)
  - Finished-product moisture content exceeds the product's maximum allowable level (`:moisture-content-exceeds-max`)
  - Mycotoxin (aflatoxin) contamination exceeds the product's maximum allowable level (`:mycotoxin-level-exceeds-max`)
  - Guaranteed-analysis nutrient deviation exceeds the product's maximum allowable deviation (`:nutrient-deviation-exceeds-max`)
  - Foreign material detected on the batch's own inspection — metal/glass/dense-plastic fragments (`:foreign-material-detected`)
  - Metal-detector calibration overdue (`:metal-detector-calibration-overdue`)
  - Finished-product weight variance excessive (`:weight-variance-excessive`)
  - Medicated-feed cross-contact mismatch — a cross-contact risk (e.g. ionophore/coccidiostat carryover) not fully covered by the declared-medicated-status label (`:medicated-feed-cross-contact`)
  - Plant sanitation/cross-contamination-control score insufficient (`:sanitation-score-insufficient`)
  - Packaging integrity (bag seal / bulk-container closure) compromised (`:packaging-integrity-compromised`)
  - Shelf life exceeded — elapsed time since production beyond the product's maximum shelf-life hours (`:shelf-life-exceeded`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (e.g. mycotoxin contamination, medicated-feed cross-contact) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log mixing/pelletizing batch, nutrient-content data into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose mixing/pelletizing-equipment maintenance for mixers, steam conditioners, pellet mills, coolers, bagging/loading lines (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety concern (e.g. mycotoxin contamination, medicated-feed cross-contact); always escalates
- **`:coordinate-shipment`** — Coordinate outbound feed shipment (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct mixing-line/pelletizing-line/cooling-line/bagging-line control, or food-safety certification — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
