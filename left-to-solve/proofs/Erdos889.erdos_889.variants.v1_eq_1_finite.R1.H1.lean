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

