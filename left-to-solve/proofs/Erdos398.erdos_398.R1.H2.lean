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
