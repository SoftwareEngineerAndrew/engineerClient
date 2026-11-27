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
