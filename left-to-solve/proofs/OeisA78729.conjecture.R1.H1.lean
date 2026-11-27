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
