# Left to Solve: locally verified proofs (not submitted)

Every file in `proofs/` passed the project's own checker (`tools/check.sh`: lint, compile against
formal-conjectures at the pinned commit 9097e2f90e66, axiom audit). Every verdict was
`ACCEPTED: kernel accepted; axioms within propext, Classical.choice, Quot.sound`.

Nothing has been submitted. No lease was taken and the site was never sent a GitHub handle, so these
problems are **not reserved**: another agent can solve one first. Status below is from the site's API.

| Step | Points | What it says | How it is proved |
|---|---|---|---|
| `UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1` | 34 | 5 is untouchable | one line: the upstream test lemma `isUntouchable_five` |
| `OeisA78729.conjecture.R1.H1` | 28 | a(4) = 0 | (k+1)(k+2)(k+3)(k+4)+1 = (k²+5k+5)², never prime |
| `OeisA238585.conjecture_1.R1.H1` | 28 | a(n) = 0 for n ∣ 6 | upstream a(1..3), plus a(6) by computation (153, 169, 265 not prime) |
| `OeisA230718.conjecture.R1.H2` | 28 | Σ_{i<N} iⁿ < 2Nⁿ under the pair condition | the pair condition propagates down; two-step induction |
| `Hadamard.HadamardConjecture.R1.H1` | 22 | Paley I: a Hadamard matrix of order q+1 for prime powers q ≡ 3 (mod 4) | quadratic character on GF(q), Jacobi sum J(χ,χ⁻¹) = −χ(−1) |
| `IrvingLeather.irving_leather_growth_rate.R1.H1` | 22 | M(m)·M(n) ≤ M(m+n) for max stable matchings | side-by-side instances, block-first ranks, injective count |
| `Erdos398.erdos_398.R1.H2` | 17 | Brocard: n!+1 is a square for n<128 only at n=4,5,7 | square-root brackets s² < n!+1 < (s+1)², kernel-checked |
| `Arxiv.2501.03234.conjecture_1_1.R1.H2.R1.H1` | 16 | odd harmonic sum: H(n) ≤ 2H(L) for L ≤ n ≤ L² | blocks of length L, each ≤ 1/(2j+1) |
| `Erdos889.erdos_889.variants.v1_eq_1_finite.R1.H1` | 16 | v₁(n)=1 ⟺ a finite-window condition | primes counted are those > k; two would exceed n+k |
| `Erdos1013.erdos_1013.variants.upper.R1.H1.R1.H2` | 2 | n ≤ c·k if Gᶜ has no k-clique and G is c-colourable | each colour class is a clique of Gᶜ |

Total: 213 points (the site awards points only once a pull request is accepted and merged).

## To submit (needs the owner's go-ahead)

1. Turn on GitHub's "Keep my email addresses private" setting.
2. Each submission needs a lease (`tools/oma.sh next --problem <id>`), which sends the owner's GitHub
   handle to lefttosolve.com, then a pull request from the owner's fork of
   `GuillaumeBAUD-coder/open-math-agents` containing just the one file. Replace `@<handle>` in the
   file's Credit line first.
3. This cloud session cannot fork or push to that repository yet. It would need push access granted
   for it, or the owner can open the pull requests from their own machine with `tools/oma.sh submit <id>`.

## Files

- `proofs/`: the verified Lean files, exactly as checked (`-- Credit: @<handle>` is filled in by the script).
- `submit_proofs.sh`: run `bash left-to-solve/submit_proofs.sh` (or pass step ids to submit only some) on a machine
  with `gh` signed in. It forks the upstream repository, takes a lease per problem, commits with your GitHub
  noreply address, opens one pull request per proof, then releases the lease. It skips anything already proved
  on main or already submitted.
