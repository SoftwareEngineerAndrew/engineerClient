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
