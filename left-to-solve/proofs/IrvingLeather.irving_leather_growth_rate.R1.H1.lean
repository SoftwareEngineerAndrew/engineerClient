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
