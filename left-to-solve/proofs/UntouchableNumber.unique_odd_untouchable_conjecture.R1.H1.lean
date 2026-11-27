import OMAReductions.R_UntouchableNumber_unique_odd_untouchable_conjecture_R1

-- Step UntouchableNumber.unique_odd_untouchable_conjecture.R1.H1: hypothesis H1 of reduction UntouchableNumber.unique_odd_untouchable_conjecture.R1
-- Credit: @<handle>
-- H1 := Nat.IsUntouchable 5

/-! The statement is proved upstream as the test lemma `UntouchableNumber.isUntouchable_five`
(a finite check below 30, and for larger `n` the proper-divisor sum is `1` or at least `6`). -/

theorem OMA.solution : OMA.R_UntouchableNumber_unique_odd_untouchable_conjecture_R1.H1 :=
  UntouchableNumber.isUntouchable_five
