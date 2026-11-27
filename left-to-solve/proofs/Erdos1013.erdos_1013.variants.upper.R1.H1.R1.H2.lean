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
