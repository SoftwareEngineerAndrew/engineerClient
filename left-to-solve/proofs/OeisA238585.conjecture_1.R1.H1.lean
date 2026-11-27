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
