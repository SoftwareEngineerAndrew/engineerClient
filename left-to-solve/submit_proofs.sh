#!/usr/bin/env bash
# Submit locally verified Left to Solve proofs as pull requests (one file per PR), from your own fork.
# Every file below passed tools/check.sh (lint + compile against formal-conjectures 9097e2f90e66 + axiom audit).
# Needs: git, gh (signed in: gh auth status), curl, python3. No Lean install needed: CI re-checks each PR.
# Usage: bash submit_proofs.sh            (all)      |   bash submit_proofs.sh <step id> ...   (some)
set -euo pipefail
SITE=https://lefttosolve.com
UPSTREAM=GuillaumeBAUD-coder/open-math-agents
AGENT=claude-code
for t in git gh curl python3; do command -v "$t" >/dev/null || { echo "missing $t" >&2; exit 1; }; done
gh auth status >/dev/null 2>&1 || { echo "run: gh auth login" >&2; exit 1; }
HANDLE=$(gh api user --jq .login)
EMAIL="$(gh api user --jq .id)+${HANDLE}@users.noreply.github.com"   # keeps your real email out of the commits
WORK=${WORK:-$HOME/open-math-agents-submit}
jget() { python3 -I -c 'import json,sys;d=json.load(sys.stdin)
for k in sys.argv[1].split("."): d=d.get(k) if isinstance(d,dict) else None
print("" if d is None else d)' "$1"; }
enc() { python3 -I -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }

if [ ! -d "$WORK/.git" ]; then
  gh repo fork "$UPSTREAM" --clone=false >/dev/null 2>&1 || true
  git clone -q --filter=blob:none "https://github.com/$HANDLE/open-math-agents.git" "$WORK"
  git -C "$WORK" remote add upstream "https://github.com/$UPSTREAM.git"
fi
cd "$WORK"
git config user.name "$HANDLE"
git config user.email "$EMAIL"
git fetch -q upstream main

write_proof() {
  case "$1" in
    Arxiv.2501.03234.conjecture_1_1.R1.H2.R1.H1)
      cat > "proofs/Arxiv.2501.03234.conjecture_1_1.R1.H2.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_Arxiv_2501_03234_conjecture_1_1_R1_H2_R1

-- Step Arxiv.2501.03234.conjecture_1_1.R1.H2.R1.H1: hypothesis H1 of reduction Arxiv.2501.03234.conjecture_1_1.R1.H2.R1
-- Credit: @<handle>
-- H1 := ∀ n L : ℕ, L ≤ n → n ≤ L^2 → ∑ k ∈ range n, (1 : ℚ)/(2*k+1) ≤ 2*∑ k ∈ range L, (1 : ℚ)/(2*k+1)

/-! Split `[0, L^2)` into `L` blocks of length `L`. The block starting at `(j+1) L` has
`L` terms, each at most `1/(2(j+1)L+1)`, so it sums to at most `1/(2j+1)`: blocks `1..L-1`
together are bounded by the first `L - 1` terms, hence by the first block. The sum up to `n`
is at most the sum up to `L^2` since the terms are positive. -/
open Finset

lemma OMA.block_le (L j : ℕ) :
    ∑ i ∈ range L, (1 : ℚ) / (2 * (((j + 1) * L + i : ℕ) : ℚ) + 1) ≤ 1 / (2 * (j : ℚ) + 1) := by
  have hterm : ∀ i ∈ range L, (1 : ℚ) / (2 * (((j + 1) * L + i : ℕ) : ℚ) + 1)
      ≤ 1 / (2 * (((j + 1) * L : ℕ) : ℚ) + 1) := by
    intro i _
    apply one_div_le_one_div_of_le (by positivity)
    have : (((j + 1) * L : ℕ) : ℚ) ≤ (((j + 1) * L + i : ℕ) : ℚ) := by
      exact_mod_cast Nat.le_add_right _ _
    linarith
  calc ∑ i ∈ range L, (1 : ℚ) / (2 * (((j + 1) * L + i : ℕ) : ℚ) + 1)
      ≤ ∑ _i ∈ range L, (1 : ℚ) / (2 * (((j + 1) * L : ℕ) : ℚ) + 1) := sum_le_sum hterm
    _ = (L : ℚ) / (2 * (((j + 1) * L : ℕ) : ℚ) + 1) := by
      rw [sum_const, card_range, nsmul_eq_mul, mul_one_div]
    _ ≤ 1 / (2 * (j : ℚ) + 1) := by
      rw [div_le_div_iff₀ (by positivity) (by positivity)]
      push_cast
      nlinarith

theorem OMA.solution : OMA.R_Arxiv_2501_03234_conjecture_1_1_R1_H2_R1.H1 := by
  intro n L hLn hnL
  have hnn : ∀ k ∈ range (L ^ 2), k ∉ range n → (0 : ℚ) ≤ 1 / (2 * (k : ℚ) + 1) :=
    fun k _ _ => by positivity
  have hblocks : ∀ j, ∑ k ∈ range ((j + 1) * L), (1 : ℚ) / (2 * (k : ℚ) + 1)
      ≤ ∑ k ∈ range L, (1 : ℚ) / (2 * (k : ℚ) + 1) + ∑ k ∈ range j, (1 : ℚ) / (2 * (k : ℚ) + 1) := by
    intro j
    induction j with
    | zero => simp
    | succ j ih =>
      rw [show (j + 1 + 1) * L = (j + 1) * L + L by ring, sum_range_add, sum_range_succ]
      have hb := OMA.block_le L j
      linarith
  rcases Nat.eq_zero_or_pos L with rfl | hL
  · have : n = 0 := by simpa using hnL
    subst this
    simp
  have h1 : ∑ k ∈ range n, (1 : ℚ) / (2 * (k : ℚ) + 1)
      ≤ ∑ k ∈ range (L ^ 2), (1 : ℚ) / (2 * (k : ℚ) + 1) :=
    sum_le_sum_of_subset_of_nonneg (range_subset_range.2 hnL) hnn
  have h2 := hblocks (L - 1)
  rw [Nat.sub_add_cancel hL, ← sq] at h2
  have h3 : ∑ k ∈ range (L - 1), (1 : ℚ) / (2 * (k : ℚ) + 1)
      ≤ ∑ k ∈ range L, (1 : ℚ) / (2 * (k : ℚ) + 1) :=
    sum_le_sum_of_subset_of_nonneg (range_subset_range.2 (Nat.sub_le L 1))
      (fun k _ _ => by positivity)
  linarith
LEAN_PROOF_EOF
      ;;
    Erdos1013.erdos_1013.variants.upper.R1.H1.R1.H2)
      cat > "proofs/Erdos1013.erdos_1013.variants.upper.R1.H1.R1.H2.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_Erdos1013_erdos_1013_variants_upper_R1_H1_R1

-- Step Erdos1013.erdos_1013.variants.upper.R1.H1.R1.H2: hypothesis H2 of reduction Erdos1013.erdos_1013.variants.upper.R1.H1.R1
-- Credit: @<handle>
-- H2 := ∀ (n k c : ℕ) (G : SimpleGraph (Fin n)), Gᶜ.CliqueFree k → G.Colorable c → n ≤ c * k

/-! Every colour class of a proper `c`-colouring of `G` is independent in `G`, so it is a
clique in `Gᶜ` and has fewer than `k` vertices. Summing over the `c` classes gives `n ≤ c * k`. -/

/-- A colour class of a proper colouring is independent in `G`, hence a clique in `Gᶜ`;
if `Gᶜ` has no `k`-clique, every colour class has fewer than `k` vertices. -/
lemma OMA.colour_class_card_lt {n k c : ℕ} {G : SimpleGraph (Fin n)}
    (hfree : Gᶜ.CliqueFree k) (C : G.Coloring (Fin c)) (i : Fin c) :
    (Finset.univ.filter (fun v => C v = i)).card < k := by
  refine lt_of_not_ge fun h => ?_
  obtain ⟨t, ht, htc⟩ := Finset.exists_subset_card_eq h
  apply hfree t
  rw [SimpleGraph.isNClique_iff]
  refine ⟨?_, htc⟩
  intro x hx y hy hxy
  rw [SimpleGraph.compl_adj]
  refine ⟨hxy, fun hadj => C.valid hadj ?_⟩
  rw [(Finset.mem_filter.1 (ht hx)).2, (Finset.mem_filter.1 (ht hy)).2]

theorem OMA.solution : OMA.R_Erdos1013_erdos_1013_variants_upper_R1_H1_R1.H2 := by
  intro n k c G hfree hcol
  obtain ⟨C⟩ := hcol
  calc n = (Finset.univ : Finset (Fin n)).card := by simp
    _ = ∑ i : Fin c, (Finset.univ.filter (fun v => C v = i)).card :=
        Finset.card_eq_sum_card_fiberwise (fun x _ => Finset.mem_univ (C x))
    _ ≤ ∑ _i : Fin c, k :=
        Finset.sum_le_sum (fun i _ => (OMA.colour_class_card_lt hfree C i).le)
    _ = c * k := by simp
LEAN_PROOF_EOF
      ;;
    Erdos398.erdos_398.R1.H2)
      cat > "proofs/Erdos398.erdos_398.R1.H2.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_Erdos398_erdos_398_R1

-- Step Erdos398.erdos_398.R1.H2: hypothesis H2 of reduction Erdos398.erdos_398.R1
-- Credit: @<handle>
-- H2 := ∀ n : ℕ, n < 128 → ((∃ m : ℕ, n.factorial + 1 = m ^ 2) ↔ n ∈ ({4, 5, 7} : Set ℕ))

/-! Brocard's equation below 128. For `n = 4, 5, 7` the witnesses are `5, 11, 71`. For every other
`n < 128`, `s = ⌊√(n! + 1)⌋` satisfies `s * s < n! + 1 < (s + 1) * (s + 1)`, so `n! + 1` is not a
square; the kernel checks each bracket with exact arithmetic. -/

/-- A number strictly between two consecutive squares is not a square. -/
lemma OMA.no_sq {N m : ℕ} (hm : N = m ^ 2) (s : ℕ) (h1 : s * s < N) (h2 : N < (s + 1) * (s + 1)) :
    False := by
  subst hm
  have hs : s < m := by
    by_contra h
    have := Nat.mul_le_mul (not_lt.mp h) (not_lt.mp h)
    rw [sq] at h1
    omega
  have hs' : m < s + 1 := by
    by_contra h
    have := Nat.mul_le_mul (not_lt.mp h) (not_lt.mp h)
    rw [sq] at h2
    omega
  omega

theorem OMA.solution : OMA.R_Erdos398_erdos_398_R1.H2 := by
  intro n hn
  constructor
  · rintro ⟨m, hm⟩
    interval_cases n
    · exact (OMA.no_sq hm 1 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · simp
    · simp
    · exact (OMA.no_sq hm 26 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · simp
    · exact (OMA.no_sq hm 200 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 602 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1904 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 6317 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 21886 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 78911 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 295259 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1143535 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 4574143 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 18859677 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 80014834 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 348776576 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1559776268 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 7147792818 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 33526120082 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 160785623545 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 787685471322 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3938427356614 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 20082117944245 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 104349745809073 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 552166953567228 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2973510046012910 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 16286585271694955 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 90679869067935485 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 512962802680363491 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2946746955341073478 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 17182339742875652406 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 101652092779175702171 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 609912556675054213027 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3709953246501409085690 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 22869687743093501007951 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 142821154179615294686593 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 903280290523322408635610 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 5783815921445270815783609 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 37483411234209726053065805 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 245795164849461258960674062 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1630420674178430788228519563 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 10937194378152021970306618007 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 74179661362209580727623742159 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 508550136674023695658451670185 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3523338699662022653505900576721 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 24663370897634158574541304037050 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 174396368086360611696209329639024 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1245439180886558699493562057691804 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 8980989654316715588967781706572076 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 65382591597917144387816492317568177 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 480461962427038942460267525096444474 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3563201278858419461033351267854721464 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 26664556771205919519070097139612996000 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 201312988912482288333668455069536465757 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1533154046820761769413164705689608744377 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 11776379687564843276211019969710858039009 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 91219444817107882594696857529818207676198 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 712446639319201784948673912308403605115342 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 5609810447812647575362248801595614968784558 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 44526490041372451122965980435912297622389065 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 356211920330979608983727843487298380979112523 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2871872314724746021942727901945240734448707786 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 23331200978034608323876057832648816217523382535 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 190974110596668796970008672429388554580114244205 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1574812859496908794403637793960093262759360945119 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 13081378078327271990661335578798848847474255303826 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 109446661301155695857080695109221322834464193656741 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 922213960297642814598347871007016379244405330655250 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 7825244940376376925358096892704591704511772131306815 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 66858922078602825324590583356376523422703411874063526 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 575142194723999224356836312510183507170717503745407529 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 4980877514193196669713282991946078429827937232372941867 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 43422283469044442400520987277954690033900570230313299933 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 381028991060110634246276414878912279469899189376847948137 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3365156932181068109459677272856044111292549448241189337343 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 29910169058002623210200515287548862104836069367192860122492 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 267524684928818862621490012042605003730753817304274266583374 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2407722164359369763593410108383445033576784355738468399250368 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 21802851503903891305843592056331800458090082265244558375673041 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 198633430462262788036763464177703883166690275063913206990548481 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1820505461284132832359203813645046756110583331925714370170257092 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 16784231035053557904028966906346483792025484094796637394973403970 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 155650555359345674201535001388480503193835670087005087695666582899 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1451811729660401840498379775717372701145990271033308799231637667601 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 13619201234191322393627253212934023042404388731461906952430812397994 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 128483287477042947436606854413089420338280480054241478815100633956558 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1218899489080933816973227253068021382231629321448981884930371490328689 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 11627560052213890684239693812535151882288553865765313903995958273943586 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 111527638075238136262755547542307772029800712447380847367329167783382104 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1075533591796017115343430456551200439746548977577162936545074776552498754 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 10427685057848376925507942191442630012584828624363222101172323570579332599 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 101636501751285493870798488028947073709983082260934656117721845292980988580 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 995830274128553338795685900500337369492022050359737013283228306377142743139 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 9807790764615756210934052418079289148346460527555220609613824741800936687334 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 97092175013660332284448160034192795594426266264944604365617979012105653222056 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 966054943799492973133000870362309068674974070396662776244736194062917963496762 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 9660549437994929731330008703623090686749740703966627762447361940629179634967623 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 97087320283538361860527309219842556706614048406497393159303888737041105661341886 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 980533870655936423948625541326149462775640364901662691744776797397063645922018558 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 9951331929187258502732236960185323062286269196016882430577798916700031964531492841 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 101484071386329527885209097242248445236636861390059750092770501250120539741035912275 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1039902283024847917130442479668355681880681037149513624575745076720460749316250325718 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 10706449288791818466268336278478032542357250736600016133856467330324429824323778312632 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 110748372592834877256996271322478239333596957884433122790811629506376644324689283182593 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1150930849118151396203444749571140056142251512738730748889046422484306994948185555067062 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 12016070835354182374265387937522069627669489723001090285796577272391092565383658077767525 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 126025614123587707293295876079117512537385342131235696615275004912895251195083773777278713 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1327762234396747957102518441431304475318248542810307723245281586507737091174592866966317021 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 14051714689748984593623962505239079868348052594780832925663847484470244491394851109614402124 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 149371776070977131787360943527332592927665828376130329195744662120431138890413125151274108025 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1594854141754720953366019534553668567051878399708964981408759120209126426332307816057926729278 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 17102905289724946876810411380732062891250259623212799140339105513258487281478468744088870539836 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 184203927331962664677022195613714175289873806750604681693578915410549137313659497763356116645156 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 1992470115411701951733529798121321620988521552635057139913674325787109866466242825099576545023806 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 21643765498993678240886932377778118080730791109571219221465841941270509626237168436808252299687104 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 236105606905206846282664890558362403647951978515981481952412322048434952117837438819546766063940584 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 2586407337108586045310991770677553531245102834944805618026162911581903627374073073000747058630903971 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 28450480708194446498420909477453088843696131184392861798287792027400939901114803803008217644939943684 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 314245830534529150853639626024900715446450496615794609384389709639185275103382116559145270275525398121 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 3485154855530142677187466320446441357981127778882352371947192897165646416292211396888877799429663403919 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 38809042007129483893781565406865620834798797710240359699213981355783156401686990286633697030906986434922 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 433898280347932019750481702625682793859075389113838895819077693640544086702009849212692471607705240138022 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 4870496117317050983460516300138638445936066639921665763111864864036446435669658599725141682550247577916338 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
    · exact (OMA.no_sq hm 54887703709097355030423828870959689537912082325570463956237729988887076289282989288176887203138202686949856 (by norm_num [Nat.factorial]) (by norm_num [Nat.factorial])).elim
  · intro h
    simp only [Set.mem_insert_iff, Set.mem_singleton_iff] at h
    rcases h with rfl | rfl | rfl
    · exact ⟨5, by norm_num [Nat.factorial]⟩
    · exact ⟨11, by norm_num [Nat.factorial]⟩
    · exact ⟨71, by norm_num [Nat.factorial]⟩
LEAN_PROOF_EOF
      ;;
    Erdos889.erdos_889.variants.v1_eq_1_finite.R1.H1)
      cat > "proofs/Erdos889.erdos_889.variants.v1_eq_1_finite.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_Erdos889_erdos_889_variants_v1_eq_1_finite_R1

-- Step Erdos889.erdos_889.variants.v1_eq_1_finite.R1.H1: hypothesis H1 of reduction Erdos889.erdos_889.variants.v1_eq_1_finite.R1
-- Credit: @<handle>
-- H1 := ∀ n > 0, v_l 1 n = 1 ↔ ∀ k ≥ 1, k ^ 2 < n → #{p ∣ n + k prime : k < p} ≤ 1

/-! A prime factor of `n + k` divides none of `n, ..., n + k - 1` exactly when it exceeds `k`,
so `v n k` counts the prime factors of `n + k` above `k`. For `n ≤ k ^ 2` two such primes
would give `(k + 1) ^ 2 ≤ n + k`, impossible; and `k = 1` always contributes a prime of
`n + 1`, so the supremum over `k ≥ 1` is exactly `1` iff the bound holds in the finite window. -/

open Finset

lemma OMA.v_eq (n k : ℕ) :
    Erdos889.v n k = ((n + k).primeFactors.filter (fun p => k < p)).card := by
  unfold Erdos889.v
  congr 1
  apply Finset.filter_congr
  intro p hp
  have hpp : p.Prime := Nat.prime_of_mem_primeFactors hp
  have hpd : p ∣ n + k := Nat.dvd_of_mem_primeFactors hp
  have h2 := hpp.two_le
  constructor
  · intro h
    by_contra hle
    apply h (k - p) (Finset.mem_range.2 (by omega))
    have heq : n + (k - p) = (n + k) - p := by omega
    rw [heq]
    exact Nat.dvd_sub hpd dvd_rfl
  · intro hkp i hi hdi
    have hik : i < k := Finset.mem_range.1 hi
    have hd : p ∣ k - i := by
      have heq : k - i = (n + k) - (n + i) := by omega
      rw [heq]
      exact Nat.dvd_sub hpd hdi
    have := Nat.le_of_dvd (by omega) hd
    omega

lemma OMA.card_le_one_of_sq (n k : ℕ) (hn : 0 < n) (hk : n ≤ k ^ 2) :
    ((n + k).primeFactors.filter (fun p => k < p)).card ≤ 1 := by
  rw [Finset.card_le_one]
  intro p hp q hq
  by_contra hne
  simp only [Finset.mem_filter, Nat.mem_primeFactors] at hp hq
  obtain ⟨⟨hpp, hpd, -⟩, hkp⟩ := hp
  obtain ⟨⟨hqp, hqd, -⟩, hkq⟩ := hq
  have hcop : Nat.Coprime p q := (Nat.coprime_primes hpp hqp).2 hne
  have hpq : p * q ∣ n + k := hcop.mul_dvd_of_dvd_of_dvd hpd hqd
  have hle : p * q ≤ n + k := Nat.le_of_dvd (by omega) hpq
  nlinarith

theorem OMA.solution : OMA.R_Erdos889_erdos_889_variants_v1_eq_1_finite_R1.H1 := by
  intro n hn
  constructor
  · intro h k hk _
    have hle : (Erdos889.v n k : ℕ∞) ≤ 1 := by
      rw [← h]
      unfold Erdos889.v_l
      exact le_iSup₂_of_le k hk le_rfl
    rw [← OMA.v_eq]
    exact_mod_cast hle
  · intro h
    unfold Erdos889.v_l
    apply le_antisymm
    · apply iSup₂_le
      intro k hk
      rw [OMA.v_eq]
      have hc : ((n + k).primeFactors.filter (fun p => k < p)).card ≤ 1 := by
        by_cases hkn : k ^ 2 < n
        · exact h k hk hkn
        · exact OMA.card_le_one_of_sq n k hn (by omega)
      exact_mod_cast hc
    · apply le_iSup₂_of_le 1 le_rfl
      rw [OMA.v_eq]
      have hne : ((n + 1).primeFactors.filter (fun p => 1 < p)).Nonempty := by
        obtain ⟨p, hp, hpd⟩ := Nat.exists_prime_and_dvd (show n + 1 ≠ 1 by omega)
        exact ⟨p, Finset.mem_filter.2 ⟨Nat.mem_primeFactors.2 ⟨hp, hpd, by omega⟩, hp.one_lt⟩⟩
      have hc := hne.card_pos
      exact_mod_cast hc
LEAN_PROOF_EOF
      ;;
    Hadamard.HadamardConjecture.R1.H1)
      cat > "proofs/Hadamard.HadamardConjecture.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_Hadamard_HadamardConjecture_R1

-- Step Hadamard.HadamardConjecture.R1.H1: hypothesis H1 of reduction Hadamard.HadamardConjecture.R1
-- Credit: @<handle>
-- H1 := ∀ q : ℕ, IsPrimePow q → q % 4 = 3 → ∃ M, IsHadamard' (n := q + 1) M

/-! Paley I. For a prime power `q = p ^ k` with `q % 4 = 3`, work in `F = GaloisField p k`, where
`-1` is a non-square, so the quadratic character `χ` has `χ (-1) = -1`. Index by `Option F`, with
`none` a point at infinity: row `none` is all `1`, column `none` is `-1` below it, and the `F × F`
block is `[a = b] + χ (a - b)`. A Jacobi sum computation gives `Hᵀ H = (q + 1) I`. -/

open Finset

namespace OMA.Paley

variable {F : Type*} [Field F] [Fintype F] [DecidableEq F]

/-- For `a ≠ b`: `∑ c, χ (c - a) * χ (c - b) = -1` when `χ (-1) = -1`. -/
lemma cross_sum (hF : ringChar F ≠ 2) (hneg : quadraticChar F (-1) = -1) {a b : F} (hab : a ≠ b) :
    ∑ c, quadraticChar F (c - a) * quadraticChar F (c - b) = -1 := by
  have hd : b - a ≠ 0 := sub_ne_zero.2 (Ne.symm hab)
  let e : F ≃ F := (Equiv.mulLeft₀ (b - a) hd).trans (Equiv.addLeft a)
  have he : ∀ x, e x = a + (b - a) * x := fun x => rfl
  rw [← e.sum_comp]
  have hsq : quadraticChar F (b - a) * quadraticChar F (b - a) = 1 := by
    rw [← sq]; exact quadraticChar_sq_one hd
  have hterm : ∀ x, quadraticChar F (e x - a) * quadraticChar F (e x - b) =
      -(quadraticChar F x * quadraticChar F (1 - x)) := by
    intro x
    have h1 : e x - a = (b - a) * x := by rw [he]; ring
    have h2 : e x - b = (b - a) * ((-1) * (1 - x)) := by rw [he]; ring
    rw [h1, h2, map_mul, map_mul, map_mul, hneg]
    linear_combination (-(quadraticChar F x * quadraticChar F (1 - x))) * hsq
  rw [Finset.sum_congr rfl (fun x _ => hterm x), Finset.sum_neg_distrib]
  have hJ := jacobiSum_nontrivial_inv (quadraticChar_ne_one hF)
  rw [(quadraticChar_isQuadratic F).inv, hneg, jacobiSum] at hJ
  rw [hJ]
  norm_num

/-- The Paley I matrix, indexed by `Option F` (`none` is the extra point at infinity). -/
def H (F : Type*) [Field F] [Fintype F] [DecidableEq F] : Option F → Option F → ℤ
  | none, _ => 1
  | some _, none => -1
  | some a, some b => if a = b then 1 else quadraticChar F (a - b)

lemma H_none_left (y : Option F) : H F none y = 1 := by cases y <;> rfl

lemma H_some_none (a : F) : H F (some a) none = -1 := rfl

lemma H_some (a b : F) :
    H F (some a) (some b) = (if a = b then 1 else 0) + quadraticChar F (a - b) := by
  simp only [H]
  split_ifs with h
  · subst h; simp
  · simp

lemma H_sq (x y : Option F) : H F x y * H F x y = 1 := by
  rcases x with _ | a <;> rcases y with _ | b
  · rfl
  · rfl
  · rfl
  · simp only [H]
    split_ifs with h
    · rfl
    · rw [← sq]; exact quadraticChar_sq_one (sub_ne_zero.2 h)

lemma col_sum (hF : ringChar F ≠ 2) (b : F) : ∑ a, H F (some a) (some b) = 1 := by
  simp only [H_some, Finset.sum_add_distrib, Finset.sum_ite_eq', Finset.mem_univ, if_true]
  have hs : ∑ a, quadraticChar F (a - b) = ∑ a, quadraticChar F a :=
    (Equiv.subRight b).sum_comp (fun c => quadraticChar F c)
  rw [hs, quadraticChar_sum_zero hF, add_zero]

lemma cross (hF : ringChar F ≠ 2) (hneg : quadraticChar F (-1) = -1) {a b : F} (hab : a ≠ b) :
    ∑ c, H F (some c) (some a) * H F (some c) (some b) = -1 := by
  have hba : quadraticChar F (b - a) = -quadraticChar F (a - b) := by
    rw [show b - a = (-1) * (a - b) by ring, map_mul, hneg]; ring
  simp only [H_some, add_mul, mul_add, Finset.sum_add_distrib, ite_mul, mul_ite, one_mul, mul_one,
    zero_mul, mul_zero, Finset.sum_ite_eq', Finset.mem_univ, if_true]
  rw [cross_sum hF hneg hab]
  simp [Ne.symm hab, hba]

lemma orth (hF : ringChar F ≠ 2) (hneg : quadraticChar F (-1) = -1) (x y : Option F) :
    ∑ z, H F z x * H F z y = if x = y then (Fintype.card F + 1 : ℤ) else 0 := by
  rw [Fintype.sum_option]
  rcases x with _ | a <;> rcases y with _ | b
  · simp [H_none_left, H_some_none]
    ring
  · simp only [H_none_left, H_some_none, neg_one_mul, Finset.sum_neg_distrib, col_sum hF b]
    simp
  · simp only [H_none_left, H_some_none, mul_neg_one, Finset.sum_neg_distrib, col_sum hF a]
    simp
  · rw [H_none_left, H_none_left]
    by_cases hab : a = b
    · subst hab
      simp only [H_sq, Finset.sum_const, Finset.card_univ, if_true]
      simp
      ring
    · rw [cross hF hneg hab]
      simp [hab]

end OMA.Paley

theorem OMA.solution : OMA.R_Hadamard_HadamardConjecture_R1.H1 := by
  intro q hq h4
  obtain ⟨p, k, hp, hk, rfl⟩ := (isPrimePow_nat_iff q).1 hq
  have := Fact.mk hp
  classical
  let _ : Fintype (GaloisField p k) := Fintype.ofFinite _
  have hcard : Fintype.card (GaloisField p k) = p ^ k := by
    rw [← Nat.card_eq_fintype_card]; exact GaloisField.card p k hk.ne'
  have hp2 : p ≠ 2 := by
    rintro rfl
    rcases k with _ | _ | k
    · omega
    · norm_num at h4
    · have h4d : 4 ∣ 2 ^ (k + 1 + 1) := Dvd.intro (2 ^ k) (by ring)
      omega
  have hF : ringChar (GaloisField p k) ≠ 2 := by rw [ringChar.eq (GaloisField p k) p]; exact hp2
  have hneg : quadraticChar (GaloisField p k) (-1) = -1 := by
    rw [quadraticChar_neg_one_iff_not_isSquare, FiniteField.isSquare_neg_one_iff, hcard]
    omega
  have hcO : Fintype.card (Option (GaloisField p k)) = p ^ k + 1 := by
    rw [Fintype.card_option, hcard]
  let e : Option (GaloisField p k) ≃ Fin (p ^ k + 1) := Fintype.equivFinOfCardEq hcO
  refine ⟨Matrix.of fun i j => (OMA.Paley.H (GaloisField p k) (e.symm i) (e.symm j) : ℝ), ?_, ?_⟩
  · intro i j
    rcases mul_self_eq_one_iff.1 (OMA.Paley.H_sq (e.symm i) (e.symm j)) with h | h
    · simp [h]
    · simp [h]
  · ext i j
    rw [Matrix.mul_apply, Matrix.natCast_apply]
    simp only [Matrix.transpose_apply, Matrix.of_apply]
    have hsum : ∑ x, ((OMA.Paley.H (GaloisField p k) (e.symm x) (e.symm i) : ℤ) : ℝ) *
        ((OMA.Paley.H (GaloisField p k) (e.symm x) (e.symm j) : ℤ) : ℝ) =
        ((∑ z, OMA.Paley.H (GaloisField p k) z (e.symm i) *
          OMA.Paley.H (GaloisField p k) z (e.symm j) : ℤ) : ℝ) := by
      push_cast
      exact e.symm.sum_comp (fun z => ((OMA.Paley.H (GaloisField p k) z (e.symm i) : ℤ) : ℝ) *
        ((OMA.Paley.H (GaloisField p k) z (e.symm j) : ℤ) : ℝ))
    rw [hsum, OMA.Paley.orth hF hneg, hcard]
    simp only [e.symm.injective.eq_iff]
    split_ifs with hij
    · subst hij; simp
    · simp
LEAN_PROOF_EOF
      ;;
    IrvingLeather.irving_leather_growth_rate.R1.H1)
      cat > "proofs/IrvingLeather.irving_leather_growth_rate.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_IrvingLeather_irving_leather_growth_rate_R1

-- Step IrvingLeather.irving_leather_growth_rate.R1.H1: hypothesis H1 of reduction IrvingLeather.irving_leather_growth_rate.R1
-- Credit: @<handle>
-- H1 := ∀ m n : ℕ, maxStableMatchings m * maxStableMatchings n ≤ maxStableMatchings (m + n)

/-! Put an optimal instance of size `m` and one of size `n` side by side: every participant ranks
the people of the first block above those of the second, keeping their own order inside each
block. A pair across the blocks never blocks (whoever of the two is in the first block prefers
their own partner), so every pair of stable matchings of the parts gives a stable matching of the
whole, injectively. -/

open StableMarriage

namespace OMA.IL

/-- Two permutations of the blocks, as one permutation of `Fin (m + n)`. -/
def join {m n : ℕ} (σ : Equiv.Perm (Fin m)) (τ : Equiv.Perm (Fin n)) : Equiv.Perm (Fin (m + n)) :=
  finSumFinEquiv.permCongr (Equiv.sumCongr σ τ)

lemma join_castAdd {m n : ℕ} (σ : Equiv.Perm (Fin m)) (τ : Equiv.Perm (Fin n)) (a : Fin m) :
    join σ τ (Fin.castAdd n a) = Fin.castAdd n (σ a) := by
  simp [join, Equiv.permCongr_apply]

lemma join_natAdd {m n : ℕ} (σ : Equiv.Perm (Fin m)) (τ : Equiv.Perm (Fin n)) (b : Fin n) :
    join σ τ (Fin.natAdd m b) = Fin.natAdd m (τ b) := by
  simp [join, Equiv.permCongr_apply]

lemma join_symm_castAdd {m n : ℕ} (σ : Equiv.Perm (Fin m)) (τ : Equiv.Perm (Fin n)) (a : Fin m) :
    (join σ τ).symm (Fin.castAdd n a) = Fin.castAdd n (σ.symm a) := by
  rw [Equiv.symm_apply_eq, join_castAdd, Equiv.apply_symm_apply]

lemma join_symm_natAdd {m n : ℕ} (σ : Equiv.Perm (Fin m)) (τ : Equiv.Perm (Fin n)) (b : Fin n) :
    (join σ τ).symm (Fin.natAdd m b) = Fin.natAdd m (τ.symm b) := by
  rw [Equiv.symm_apply_eq, join_natAdd, Equiv.apply_symm_apply]

lemma castAdd_lt_castAdd {m n : ℕ} (u v : Fin m) :
    (Fin.castAdd n u : Fin (m + n)) < Fin.castAdd n v ↔ u < v := by
  rw [Fin.lt_def, Fin.lt_def, Fin.val_castAdd, Fin.val_castAdd]

lemma natAdd_lt_natAdd {m n : ℕ} (u v : Fin n) :
    (Fin.natAdd m u : Fin (m + n)) < Fin.natAdd m v ↔ u < v := by
  rw [Fin.lt_def, Fin.lt_def, Fin.val_natAdd, Fin.val_natAdd]
  omega

lemma not_natAdd_lt_castAdd {m n : ℕ} (u : Fin n) (v : Fin m) :
    ¬ (Fin.natAdd m u : Fin (m + n)) < Fin.castAdd n v := by
  rw [Fin.lt_def, Fin.val_natAdd, Fin.val_castAdd]
  omega

/-- Side by side: everyone ranks the first block above the second, keeping their own order
inside their own block. -/
def prof {m n : ℕ} (p : Profile m) (q : Profile n) : Profile (m + n) where
  manRank x := Fin.addCases (fun a => join (p.manRank a) 1) (fun b => join 1 (q.manRank b)) x
  womanRank x := Fin.addCases (fun a => join (p.womanRank a) 1) (fun b => join 1 (q.womanRank b)) x

lemma stable_join {m n : ℕ} {p : Profile m} {q : Profile n} {μ : Matching m} {ν : Matching n}
    (hμ : IsStable p μ) (hν : IsStable q ν) : IsStable (prof p q) (join μ ν) := by
  intro x y hb
  obtain ⟨hne, hman, hwoman⟩ := hb
  induction x using Fin.addCases with
  | left a =>
    induction y using Fin.addCases with
    | left b =>
      apply hμ a b
      refine ⟨fun h => hne (by rw [join_castAdd, h]), ?_, ?_⟩
      · simpa [prof, join_castAdd, castAdd_lt_castAdd] using hman
      · simpa [prof, join_castAdd, join_symm_castAdd, castAdd_lt_castAdd] using hwoman
    | right b =>
      simp only [prof, Fin.addCases_left, join_castAdd, join_natAdd, Equiv.Perm.one_apply] at hman
      exact not_natAdd_lt_castAdd _ _ hman
  | right a =>
    induction y using Fin.addCases with
    | left b =>
      simp only [prof, Fin.addCases_left, join_symm_castAdd, join_castAdd, join_natAdd,
        Equiv.Perm.one_apply] at hwoman
      exact not_natAdd_lt_castAdd _ _ hwoman
    | right b =>
      apply hν a b
      refine ⟨fun h => hne (by rw [join_natAdd, h]), ?_, ?_⟩
      · simpa [prof, join_natAdd, natAdd_lt_natAdd] using hman
      · simpa [prof, join_natAdd, join_symm_natAdd, natAdd_lt_natAdd] using hwoman

lemma join_injective {m n : ℕ} {μ μ' : Equiv.Perm (Fin m)} {ν ν' : Equiv.Perm (Fin n)}
    (h : join μ ν = join μ' ν') : μ = μ' ∧ ν = ν' := by
  constructor
  · refine Equiv.ext fun a => ?_
    have := congrArg (fun f => f (Fin.castAdd n a)) h
    simpa [join_castAdd] using this
  · refine Equiv.ext fun b => ?_
    have := congrArg (fun f => f (Fin.natAdd m b)) h
    simpa [join_natAdd] using this

lemma card_mul_le {m n : ℕ} (p : Profile m) (q : Profile n) :
    numStableMatchings p * numStableMatchings q ≤ numStableMatchings (prof p q) := by
  classical
  unfold numStableMatchings
  rw [← Fintype.card_prod]
  refine Fintype.card_le_of_injective
    (fun x => ⟨join x.1.1 x.2.1, stable_join x.1.2 x.2.2⟩) ?_
  rintro ⟨⟨μ, hμ⟩, ⟨ν, hν⟩⟩ ⟨⟨μ', hμ'⟩, ⟨ν', hν'⟩⟩ h
  have h' := join_injective (congrArg Subtype.val h)
  obtain ⟨rfl, rfl⟩ := h'
  rfl

end OMA.IL

theorem OMA.solution : OMA.R_IrvingLeather_irving_leather_growth_rate_R1.H1 := by
  intro m n
  obtain ⟨p, -, hp⟩ := Finset.exists_mem_eq_sup (Finset.univ : Finset (Profile m))
    Finset.univ_nonempty (fun p => numStableMatchings p)
  obtain ⟨q, -, hq⟩ := Finset.exists_mem_eq_sup (Finset.univ : Finset (Profile n))
    Finset.univ_nonempty (fun q => numStableMatchings q)
  unfold IrvingLeather.maxStableMatchings
  rw [hp, hq]
  exact (OMA.IL.card_mul_le p q).trans
    (Finset.le_sup (f := fun p : Profile (m + n) => numStableMatchings p) (Finset.mem_univ _))
LEAN_PROOF_EOF
      ;;
    OeisA230718.conjecture.R1.H2)
      cat > "proofs/OeisA230718.conjecture.R1.H2.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_OeisA230718_conjecture_R1

-- Step OeisA230718.conjecture.R1.H2: hypothesis H2 of reduction OeisA230718.conjecture.R1
-- Credit: @<handle>
-- H2 := ∀ n N, 3 < n → 3 ≤ N → N ≤ 3 * n → (N - 1) ^ n + (N - 2) ^ n ≤ N ^ n → ∑ i ∈ range N, i ^ n < 2 * N ^ n

/-! The pair inequality at `N` holds at every `2 ≤ j ≤ N` (the ratios `(j - 1) / j` and `(j - 2) / j`
grow with `j`). Then `T j = ∑ i < j, i ^ n` satisfies `T j ≤ j ^ n + T (j - 2)` and, since
`2 (j - 2) ^ n ≤ (j - 1) ^ n + (j - 2) ^ n ≤ j ^ n`, two-step induction gives `T j < 2 j ^ n`. -/

open Finset

/-- The hypothesis at `N` propagates down to every `2 ≤ j ≤ N`, because `(j - 1) / j` and
`(j - 2) / j` increase with `j`. -/
lemma OMA.pair_le {n N j : ℕ} (hj : 2 ≤ j) (hjN : j ≤ N)
    (h : (N - 1) ^ n + (N - 2) ^ n ≤ N ^ n) : (j - 1) ^ n + (j - 2) ^ n ≤ j ^ n := by
  obtain ⟨a, rfl⟩ : ∃ a, j = a + 2 := ⟨j - 2, by omega⟩
  obtain ⟨b, rfl⟩ : ∃ b, N = a + 2 + b := ⟨N - (a + 2), by omega⟩
  simp only [show a + 2 - 1 = a + 1 by omega, show a + 2 - 2 = a by omega,
    show a + 2 + b - 1 = a + b + 1 by omega, show a + 2 + b - 2 = a + b by omega] at h ⊢
  have h1 : ((a + 1) * (a + 2 + b)) ^ n ≤ ((a + b + 1) * (a + 2)) ^ n :=
    Nat.pow_le_pow_left (by nlinarith) n
  have h2 : (a * (a + 2 + b)) ^ n ≤ ((a + b) * (a + 2)) ^ n :=
    Nat.pow_le_pow_left (by nlinarith) n
  rw [mul_pow, mul_pow] at h1 h2
  have hpos : 0 < (a + 2 + b) ^ n := by positivity
  refine Nat.le_of_mul_le_mul_right ?_ hpos
  calc ((a + 1) ^ n + a ^ n) * (a + 2 + b) ^ n
      = (a + 1) ^ n * (a + 2 + b) ^ n + a ^ n * (a + 2 + b) ^ n := add_mul _ _ _
    _ ≤ (a + b + 1) ^ n * (a + 2) ^ n + (a + b) ^ n * (a + 2) ^ n := add_le_add h1 h2
    _ = ((a + b + 1) ^ n + (a + b) ^ n) * (a + 2) ^ n := (add_mul _ _ _).symm
    _ ≤ (a + 2 + b) ^ n * (a + 2) ^ n := Nat.mul_le_mul_right _ h
    _ = (a + 2) ^ n * (a + 2 + b) ^ n := mul_comm _ _

theorem OMA.solution : OMA.R_OeisA230718_conjecture_R1.H2 := by
  intro n N hn hN _ h
  have hn0 : n ≠ 0 := by omega
  have key : ∀ j, 1 ≤ j → j ≤ N → (∑ i ∈ range j, i ^ n) < 2 * j ^ n := by
    intro j
    induction j using Nat.strong_induction_on with
    | _ j ih =>
      intro hj1 hjN
      rcases (show j = 1 ∨ j = 2 ∨ 3 ≤ j by omega) with rfl | rfl | hj3
      · simp [hn0]
      · have h2 : 1 ≤ 2 ^ n := Nat.one_le_two_pow
        simp [Finset.sum_range_succ, hn0]
        omega
      · obtain ⟨k, rfl⟩ : ∃ k, j = k + 2 := ⟨j - 2, by omega⟩
        rw [Finset.sum_range_succ, Finset.sum_range_succ]
        have hpair := OMA.pair_le (n := n) (j := k + 2) (by omega) hjN h
        simp only [show k + 2 - 1 = k + 1 by omega, show k + 2 - 2 = k by omega] at hpair
        have hih := ih k (by omega) (by omega) (by omega)
        have hmono : k ^ n ≤ (k + 1) ^ n := Nat.pow_le_pow_left (by omega) n
        linarith
  exact key N (by omega) le_rfl
LEAN_PROOF_EOF
      ;;
    OeisA238585.conjecture_1.R1.H1)
      cat > "proofs/OeisA238585.conjecture_1.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_OeisA238585_conjecture_1_R1

-- Step OeisA238585.conjecture_1.R1.H1: hypothesis H1 of reduction OeisA238585.conjecture_1.R1
-- Credit: @<handle>
-- H1 := ∀ n : ℕ, n ∣ 6 → OeisA238585.a n = 0

/-! The divisors of 6 are 1, 2, 3 and 6. The first three values are upstream test lemmas;
`a 6 = 0` is a direct computation. -/

/-- `a 6 = 0`: with `prime 6 = 13`, the candidates are `3^2 + 12^2 = 153 = 9 * 17`,
`5^2 + 12^2 = 169 = 13^2` and `11^2 + 12^2 = 265 = 5 * 53`, none of them prime. -/
lemma OMA.a_six : OeisA238585.a 6 = 0 := by
  have hIco : Finset.Ico 1 6 = {1, 2, 3, 4, 5} := by decide
  simp only [OeisA238585.a, hIco, Finset.sum_insert, Finset.mem_insert, Finset.mem_singleton,
    OfNat.one_ne_ofNat, Nat.reduceEqDiff, or_self, not_false_eq_true, Finset.sum_singleton,
    Nat.reduceSub, Nat.nth_prime_one_eq_three, Nat.nth_prime_two_eq_five,
    Nat.nth_prime_four_eq_eleven, Nat.nth_prime_five_eq_thirteen]
  norm_num

theorem OMA.solution : OMA.R_OeisA238585_conjecture_1_R1.H1 := by
  intro n hn
  have hle : n ≤ 6 := Nat.le_of_dvd (by norm_num) hn
  interval_cases n
  · exact absurd hn (by decide)
  · exact OeisA238585.a_1
  · exact OeisA238585.a_2
  · exact OeisA238585.a_3
  · exact absurd hn (by decide)
  · exact absurd hn (by decide)
  · exact OMA.a_six
LEAN_PROOF_EOF
      ;;
    OeisA78729.conjecture.R1.H1)
      cat > "proofs/OeisA78729.conjecture.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_OeisA78729_conjecture_R1

-- Step OeisA78729.conjecture.R1.H1: hypothesis H1 of reduction OeisA78729.conjecture.R1
-- Credit: @<handle>
-- H1 := ∀ n : ℕ, n = 4 → OeisA78729.a n = 0

/-! `(k+1)(k+2)(k+3)(k+4) + 1 = (k^2 + 5k + 5)^2` is a square of a number at least 11, so it is
never prime and the defining set of `a 4` is empty. -/

theorem OMA.solution : OMA.R_OeisA78729_conjecture_R1.H1 := by
  intro n hn
  subst hn
  unfold OeisA78729.a
  rw [dif_neg]
  rintro ⟨k, -, hk⟩
  have h : ∏ i ∈ Finset.range 4, (k + i + 1) + 1 = (k ^ 2 + 5 * k + 5) * (k ^ 2 + 5 * k + 5) := by
    simp only [Finset.prod_range_succ, Finset.prod_range_zero]
    ring
  rw [h] at hk
  exact Nat.not_prime_mul (by nlinarith) (by nlinarith) hk
LEAN_PROOF_EOF
      ;;
    UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1)
      cat > "proofs/UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1.lean" <<'LEAN_PROOF_EOF'
import OMAReductions.R_UntouchableNumber_unique_odd_untouchable_conjecture_R1

-- Step UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1: hypothesis H1 of reduction UntouchableNumber.unique_odd_untouchable_conjecture.R1
-- Credit: @<handle>
-- H1 := Nat.IsUntouchable 5

/-! The statement is proved upstream as the test lemma `UntouchableNumber.isUntouchable_five`
(a finite check below 30, and for larger `n` the proper-divisor sum is `1` or at least `6`). -/

theorem OMA.solution : OMA.R_UntouchableNumber_unique_odd_untouchable_conjecture_R1.H1 :=
  UntouchableNumber.isUntouchable_five
LEAN_PROOF_EOF
      ;;
    *) echo "unknown id $1" >&2; return 1 ;;
  esac
  sed -i.bak "s/@<handle>/@${HANDLE}/" "proofs/$1.lean" && rm -f "proofs/$1.lean.bak"
}

submit_one() {
  local id=$1 file="proofs/$1.lean" br="oma/$1"
  if git cat-file -e "upstream/main:$file" 2>/dev/null; then echo "skip $id: already proved on main"; return; fi
  if [ "$(gh pr list --repo "$UPSTREAM" --head "$br" --state all --json number --jq length)" != 0 ]; then
    echo "skip $id: a pull request from $br already exists"; return; fi
  local R lease
  R=$(curl -sS -m 30 "$SITE/api/desk/next?handle=$(enc "$HANDLE")&agent=$AGENT&problem=$(enc "$id")")
  if [ -n "$(echo "$R" | jget error)" ]; then echo "skip $id: desk said $(echo "$R" | jget error) $(echo "$R" | jget hint)"; return; fi
  lease=$(echo "$R" | jget lease)
  git checkout -q -B "$br" upstream/main
  write_proof "$id"
  git add -- "$file"
  git commit -q -m "proof: $id" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01YVgJLLYJtwzbtUmLFKK8kV" -- "$file"
  git push -q -u origin "$br" --force-with-lease
  gh pr create --repo "$UPSTREAM" --base main --head "$HANDLE:$br" --title "$id" --body "Lease: $lease
Agent: $AGENT
Model: unknown

Problem: $SITE/p/$id
Checked locally with tools/check.sh against formal-conjectures 9097e2f90e66: kernel accepted, axioms within propext, Classical.choice, Quot.sound.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01YVgJLLYJtwzbtUmLFKK8kV"
  curl -sS -m 30 -o /dev/null -X POST "$SITE/api/desk/release?lease=$lease&reason=submitted" || true
  git checkout -q --detach upstream/main
  sleep 5   # be gentle with the desk
}

ALL=(Arxiv.2501.03234.conjecture_1_1.R1.H2.R1.H1 Erdos1013.erdos_1013.variants.upper.R1.H1.R1.H2 Erdos398.erdos_398.R1.H2 Erdos889.erdos_889.variants.v1_eq_1_finite.R1.H1 Hadamard.HadamardConjecture.R1.H1 IrvingLeather.irving_leather_growth_rate.R1.H1 OeisA230718.conjecture.R1.H2 OeisA238585.conjecture_1.R1.H1 OeisA78729.conjecture.R1.H1 UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1)
if [ $# -gt 0 ]; then IDS=("$@"); else IDS=("${ALL[@]}"); fi
for id in "${IDS[@]}"; do submit_one "$id"; done
echo "done. Your page: $SITE/a/$HANDLE"
