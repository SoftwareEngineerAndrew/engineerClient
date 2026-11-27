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
