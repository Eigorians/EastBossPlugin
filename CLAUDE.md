# CLAUDE.md

## 基本ルール
- 返答・説明・コミットメッセージは日本語。コード中のコメントも日本語
- コードはKotlinで書く（Javaのコードは出さない）

## プロジェクトの目的
BetterModelと連携し、複数のカスタムボスを実装するPaperプラグイン。
ボスごとにモデル・アニメーション・スキル・フェーズを持つ。

## 環境
- Paper 26.3 / Kotlin / Java 25 / Gradle 9.7
- BetterModel: `io.github.toxicity188:bettermodel-bukkit-api`（compileOnly、サーバーに導入済み）
- paper-plugin.yml の dependencies.server に BetterModel を required で宣言する

## コマンド
- ビルド: ./gradlew build -q
- テストサーバー: ./gradlew runServer
- ログ確認は grep -E "ERROR|WARN|Exception" で必要な部分だけ読む

## コーディング規約
- テキストはAdventure（MiniMessage）。legacyのカラーコードは禁止
- コマンドはPaperのBrigadier API（LifecycleEvents.COMMANDS）で登録
- 設定値はConfigManager経由で取得（getConfig()の直接呼び出しは禁止）
- エンティティやワールドの操作はメインスレッドで行う
- PaperやBetterModelのAPIが不明なときは推測しない。
  Gradleキャッシュの sources jar を確認する

## ボス設計方針
- 共通の抽象クラス `Boss` を作り、各ボスはそれを継承する
- ボスは `BossRegistry` に登録し、IDで召喚・管理する
- スキルは `BossSkill` として部品化し、ボス間で使い回せるようにする
- HPに応じたフェーズ移行をサポートする（`BossPhase`）
- onDisable で、全ボスのエンティティとモデルを必ず片付ける

### パッケージ構成
- `boss/` … 全ボス共通の土台（Boss / BossType / BossRegistry / BossManager / BossListener / phase / display / sound / minion）
- 手下・コンパニオンは `boss.minions.spawn(...)` で出す。ボスが倒された・消えたときに自動で削除される
- 空を飛ぶボスは `usesEntityAi = false`・`canStartSkillInAir = true` にして、onTick で自分で動かす（共通の技は終わりに `boss.resumeEntityAi()` で AI を戻すが、このボスでは戻らない）。今の技を止めて別の技を始めるのは `interruptWith`（onTick から呼ぶ）。技の外で出したエンティティは `onDispose`、死亡演出の毎tickは `onDyingTick`
- 潜る・消えるボスは `isIntangible` を true にする間、攻撃も飛び道具もすり抜けてダメージを受けない（BossListener）
- 近接技（BoneStrikeSkill）は lunge-windows の間、1振りごとに前へ踏み込める（lunge-stop-distance 0 で標的を突き抜ける）。retreat-windows の間は後ろへ下がる（振りかぶりながら間合いを取る）。長い武器が足元の相手を飛び越えないよう、close-radius / close-degrees で判定時間中は目の前の扇にも当てられる
- 技をランダムな順に続ける連携は `skill/common/RandomChainSkill`（決まった頭の技 → 候補からランダム → 締めの技）
- ボスと手下は出現時のランダム（ハスクの装備・子供など）を付けずに出す（BossManager / MinionGroup の spawn に randomizeData = false。装備も消す）
- 分身（おとり）は BetterModel の DummyTracker で同じモデルを置き、殴らせるなら Names.Key.DECOY（値はボスの UUID）を付けた Interaction を重ねる。殴られると BossListener が攻撃を止めて `Boss.onDecoyAttacked` を呼ぶ（例: bosses/assassin/AssassinClone）
- 武器の持ち替えなどは `boss.setPartVisible(ボーン名, 表示するか)`（子ボーンごと）。登場・フェーズ移行の演出は `boss.queueSkill(...)` で次の技を決める
- `skill/` … 技。`skill/common/` に使い回せる技（演出だけの PerformSkill、技を順に間を空けずに出す ChainSkill もここ）、`skill/util/` に標的探し・向きの計算・地面に沿った移動（Ground）・目に見える弾（BulletField：ブロックで止まる。bounces で跳弾）
- ボスのモデルのボーン名を `cape` で始めない（BetterModel がプレイヤーのマントの印＝CAPE タグとして扱い、プレイヤー以外では何も表示されない。マントは mantle_* などにする）。ほかにも `h_` `hi_` `p_` `glow_` などの頭の語は BetterModel の印（BoneTags）
- ボスのモデルに BetterModel の当たり判定ボーン（`b_` / `ob_` で始まる名前）を作らない。見えない当たり判定エンティティができ、それを右クリックするとクライアントがそこで止まって、ボスを見ながら盾を構えられなくなる（技の当たり判定に使うボーンは box_ など別の名前にする）
- `model/` … BetterModel のボーン姿勢・当たり判定（BoneKinematics は内部をリフレクションで読む。BetterModel 更新時は要確認）
- `status/` … プレイヤーへの状態異常（スタン・回復不可・盾のガード破壊）と、落下ダメージの保護（`status.fall.protect(プレイヤー, tick)`。打ち上げ・投げ飛ばしの後。着地したら終わる）
- `battle/` … 旅商人から始めるボス戦（参加者の募集・カウントダウン・死亡回数）。ボスと手下は参加者以外からダメージを受けず、参加者以外にダメージを与えない（技の当たり判定は `TargetFinder.isValidTarget` で参加者だけ。手下の通常攻撃などは BossListener で止める）。ボスが出現した瞬間に参加者を回復する（体力・満腹度を満タン、火を消し、スタン・回復不可・盾の破壊を解く）。戦っている間、参加者は乗り物に乗れない（乗っていたら降ろす）・装備の耐久が減らない・お腹が減らない・死亡ログと倒れた回数は参加者とボスの近く（battle.death-log-radius）の人にだけ見える。募集〜出現のカウントダウンは config.yml の battle.countdown-seconds（初期値5秒）。参加者は1人 2回（config.yml の battle.death-limit）倒れると脱落（BossBattle.isParticipant が false になり、狙われず攻撃も届かない。知らせは isMember の全員に届く）。全員脱落で敗北。討伐（記録・解放）になるのは、参加者がとどめを刺したときだけ（Boss.slainBy を BossListener の死亡イベントで決める。燃焼なら火を付けた参加者＝PlayerBurns。付けた人が脱落していれば最後に攻撃した参加者。毒などほかの継続ダメージも最後に攻撃した参加者。リロード・コマンドでの削除・時間切れ・/kill などで消えたときは討伐にしない）。勝ったとき討伐扱い（解放）になるのは脱落していない人だけ。募集を始めた人（と管理者）は旅商人のメニューの「ボス戦をやめる」（2回クリック）で中止できる。参加者が誰もいない状態が続くと撤退。参加者ごとの与ダメージを数え（BossBattle.addDamage。BossListener の MONITOR で、実際に減った HP・燃焼は火を付けた人・ほかの継続ダメージは最後に攻撃した人）、2人以上で討伐したら多い順に出す。HP は参加人数で緩やかに増える（ボス yml の health-per-player。0.2 で 1人 1倍・2人 1.2倍・3人 1.4倍）。ボスバーに経過時間を出す
- `record/` … クリアタイムの記録（records.yml。タイムは tick で保存）。1人で討伐したらソロ記録、2人以上なら集団討伐の記録（保持者は参加した全員）。どちらも保持者を名前と UUID で残し、メニューに「ソロ記録」「集団討伐ベスト」として保持者つきで出す（/eb ranking は両方）。自己ベストはソロだけ
- `progress/` … ボスの解放（progress.yml にプレイヤーごとの倒したボス）。config.yml の progression.order の順（アルファ→ガンナー→ドラゴン→暗殺者→大巨人→ベイガー→髑髏騎士）に、前のボスを倒すまで次のボス戦は始められない（参加は自由。勝てば参加者全員が倒したことになる）。OP（eastboss.progress.bypass / eastboss.admin）は自由。解放前のボスはメニューで名前・アイコンを伏せる。管理は /eb progress info|grant|revoke
- `hook/` … BetterModel との連携（BetterModelHook）。サーバーの /reload の後に自動で /bettermodel reload を実行する（BetterModelAutoReload。ServerLoadEvent の RELOAD で。config.yml の bettermodel セクション）
- `config/ConfigEditor` … /eb config <config|ボスID> <キー> [値] で設定を見る・変える（今あるキーだけ・型は今の値に合わせる・保存して読み込み直す）
- `menu/` … 旅商人のボス選択メニュー（解放の順番に並ぶ）
- `npc/` … 旅商人。ワールドに保存するので、リロード・再起動しても消えない（onDisable でも消さない）
- `bosses/<名前>/` … 個別のボス。そのボス専用の技は `bosses/<名前>/skill/`

### ボスの追加手順
1. `bosses/<名前>/` に `BossType`（object）と `Boss` のサブクラスを作る。`BossType.icon`（メニューのアイコン）は必須
2. `bosses/Bosses.kt` の `registerAll` に1行足す
3. `src/main/resources/bosses/<ボスID>.yml` を作る（共通キーは `Names.BossConfig`、技のキーは `Names.SkillConfig`）
4. 下の「ボス一覧」に追記する

- 共通のイベント処理（耐性・死亡など）は `BossListener` に書き、個別のボス名は書かない。ボス固有の振る舞いは `Boss` の onXxx を override する
- 設定値（HP・ダメージ・tick・アニメ名・ボーン名）はコードに直書きせず、ボスの yml から読む
- ボスのダメージはネザライト全身・ダメージ軽減IV・耐性I（ビーコン）を想定して、ボス yml の damage-multiplier で調整する（技の damage は素の値のまま）
- ボスの攻撃が当たったプレイヤーは回復不可になる（全ボス共通・5秒。BossListener で処理し、長さは config.yml の status.heal-lock-ticks）
- ボスが1回の攻撃で受けるダメージには上限がある（全ボス共通。config.yml の boss.max-damage-per-hit。倍率をすべて掛けた後に切り詰める）
- 飛び道具はボスに当たるが、ダメージは半分（全ボス共通。config.yml の boss.projectile-damage-multiplier。ボス yml の immunity.projectile-damage-multiplier があればそちらを使う。immunity.projectile: true ならすり抜ける）
- リスポーン直後の保護（全ボス共通。status/RespawnGuard）: リスポーンしてから config.yml の status.respawn-protection-ticks（初期値3秒）は、ボスに狙われず、ボスと手下の攻撃も当たらない（TargetFinder.isValidTarget と BossListener）。別のワールドにいる人も狙わない
- ボス本体はプレイヤーによるダメージしか受けない（全ボス共通。BossListener.onBossExternalDamage）。溶岩・マグマブロック・火・落下・窒息・落雷・ほかのモブ・持ち主の分からない爆発などは効かず、引火もしない。通るのはプレイヤーの攻撃・飛び道具、プレイヤーが付けた火（Boss.playerBurns に残っている間だけ）、毒（＝ガストへの毒矢）、/kill・奈落。コードからボスにダメージを与えるときは `entity.damage(量, プレイヤー)` のように必ずプレイヤーを渡す（渡さないと止まる）
- ボス本体（ハスク）の素の殴り攻撃は無い（全ボス共通。boss/ChaseGoal：召喚時にバニラの殴りの AI を外し、追って歩くだけの AI に入れ替える）。攻撃は必ず技で作る
- ボス本体は水に沈んでも別のモブに変化しない（全ボス共通。BossManager.stopConversion：ハスク → ゾンビの変化が始まったら毎tickの処理で打ち切る。EntityTransformEvent のキャンセルでは、装備・乗り物・チームが変化先へ移された後なので使わない）
- HP 1 で耐える・決まった攻撃しか効かないなどは `Boss.interceptDamage`（倍率と上限を掛けた後に全部の原因で呼ばれる。/kill・奈落は除く。true で止める）
- 技と技の間の隙はボス yml の skill-gap-ticks（その間は何もしない。queueSkill で決めた技は待たない）
- ジャストガード（全ボス共通。status/JustGuardManager・BossListener.onJustGuard）: 盾を右クリックで構えた瞬間から config.yml の just-guard.window-ticks の間に届いたボスの攻撃（弾・魔法も含む・ボスの方を向いているとき）は、ダメージを消して金属音を鳴らす（盾のガード破壊がたまらない。回復不可・ノックバックも無し）。技が当てた後に付けるスタンなどは `status.justGuard.guardedNow` で止める（吹き飛ばしは StatusListener が PlayerVelocityEvent で止める）。構えてから just-guard.lockout-ticks は構え直しても受付が開かない（できたらすぐ開く）。ボスごとの反応は `Boss.onJustGuard`。飛んでくる弾の弾き返しは `skill.util.Reflect.tryReflect(boss, 弾の位置, 半径)`（弾き返した人が返ってきたら、弾ごとにボスへ飛ばし直す。`Boss.onReflect`）。盾を貫く攻撃は `boss.unblockable(相手) { ダメージ }`（構えた盾を下ろさせ、ジャストガードも効かない）。ジャストガードでなくても、盾を構えてボスの方を向いているのにバニラの盾が効かなかった攻撃（構えて 5tick 未満・ボスが重なるほど近い）は BossListener がふつうのガードとして受ける（ガード破壊はたまる。`Boss.onLateGuard`）
- 盾のガード破壊（全ボス共通。status/ShieldBreakManager）: ボスの攻撃を盾で防いだダメージがボス yml の shield.break-damage たまると盾が壊れ、shield.disable-ticks の間使えない。技ごとのたまりやすさは shield.skill-multipliers（ベイガーの遠距離技は低い）。防がずにいると少しずつ減る

## ボス一覧
（ボスを作るたびに、名前・モデル名・主なスキルを追記する）

| ID | 名前 | モデル名 | アイコン | 主なスキル |
|---|---|---|---|---|
| `gunner` | ガンナー | `gunner` | クロスボウ | ロングコートの賞金稼ぎ。二丁拳銃の乱れ撃ち・横走り撃ち（レーザーサイトの後に撃つ。弾は BulletField で目に見え、壁で止まる）、狙撃（レーザーで追う→白く点滅して固定→貫通の一撃。盾も貫く＝ジャストガード・ガードが効かない（pierce-shield。`boss.unblockable`）。抜く・構える・ボルトを引く・しまう）、手榴弾（3個・赤い輪の範囲で爆発・その後に追い打ち）、蹴り（BoneStrikeSkill・boot_r）、バク宙（近づかれたら後ろへ跳び、頂点で二丁撃ち）。拳銃は弾数制（12発・本気で18発。撃ち尽くすと空撃ちの「カチッ」→ リロードで殴りどき）。プレイヤーを倒すと銃口の煙を吹いて挑発。HP 50% で本気（phase_shift でバイザーが赤くなる → ガトリング：照準から外れるほど速く振り向き（最大 14度/tick）・動きを見越して狙うので、走って振り切るのはほぼ無理（ジャストガードか物陰で受ける）。当たると少し鈍足（制圧射撃）。標的が隠れるとほかの見えている相手へ照準を移す。撃ち終わると焼き付いて殴りどき／狙撃が3連続／乱れ撃ちの弾が壁で1回跳ね返る）。壁を背にしない（下がる・跳ぶ向きは Ground.freeLength で開けた方を選び、召喚地点から extra.home-radius 以上離れると戻る方へ寄る。壁際で追い込まれるとバク宙が出やすい）。HP 20% で瀕死（決闘：コインを弾き、鐘が3回鳴り終わると射程内の全員を早撃ち。弾は物陰も貫き、ジャストガード以外は即死（盾で防いでも体力を 0 にする）。撃たれる人にだけ、撃つ cue-lead-ticks 前に撃鉄の音の合図。殴っても止まらない）。武器はボーンの表示切り替え（pistol_r/l・rifle・rifle_back・gatling）、弾は muzzle_* ボーンの位置から出る。モデルは run/model_backup/build_gunner.py で一から生成（render_anim.py でアニメを確認できる。Blockbench 5.0 形式なので、アニメの回転・位置は BetterModel がボーンと同じ向きで読む＝反転せずに書く） |
| `veigar` | ベイガー | `veigar` | アメジストの欠片 | プレイヤーの約3倍（model-scale / entity-scale）。一撃が重い（damage-multiplier 6）代わりに隙が大きい（skill-gap-ticks 20・技の end-ticks 長め）。杖の振り下ろし・薙ぎ払い（BoneStrikeSkill：プレイヤーの所までダッシュしてから振る。approach-*）、地面叩き（GroundShockwaveSkill：ジャンプでかわせる衝撃波2回）、魔法の弾（MagicBoltSkill：扇状3発・まっすぐ・横移動でかわす。近くまで来た瞬間にジャストガードで弾き返せる＝reflectable）、回転ビーム（RotatingBeamSkill：270度回転・地面を這う部分はジャンプで飛び越える）、隕石の雨（MeteorShowerSkill：宙に浮いて大笑いしながら10秒詠唱、予測マーカー付きの隕石。足元狙いは同じ人に aim-cooldown-ticks の間隔を空ける → 疲れ果てて被ダメ増加）。HP 50% で本気（rage_shift：力を溜めて叫び、目が赤くなって周りを吹き飛ばす。紫の瘴気をまとう）→ 技が強化（_rage：弾5発×2回・衝撃波3回・ビーム一周・隕石増量・踏み込みが速い）＋魔法陣の檻（MagicCageSkill・専用：予告の輪が檻になり、縁を越えるとダメージとスタン、中には魔弾が降る）。目は左右別ボーン（glow_eye_r/l）で表情、tintBones で色変更。モデルは run/model_backup/build_veigar.py で生成（元モデルの正面が +Z なので、アニメの回転は X だけ反転して書く。render_anim.py の確認はこのモデルでは当てにならない） |
| `dragon` | 焔竜 ヴォルカリス | `dragon` | ドラゴンの頭 | ちょっとまぬけでチャーミング（舌を口の端からだらんと出し、ぎょろ目の黒目が左右ちぐはぐ。どのアニメでも舌が揺れる。旋回中にときどきくしゃみ・相手を倒すと空中で一回転して大喜び・跳ね返された火球で「あちちっ」・撃ち落とされると目を回す。extra.sneeze-*）。常に飛んでいる（本体は AI と重力を切ったガスト。DragonFlight で毎tick テレポートして飛ぶ。壁・天井は collidesAt でよける）。技の合間は標的のまわりを高く旋回（extra.cruise-*。飛び道具でしか届かない。飛び道具のダメージは減らさない）。火炎ブレス（低めに浮いて首を振りながら前方を焼く。円すい・ブロックで止まる・腹の下は安全）、火球（予告の輪・着弾で燃える地面 DragonFireField。近くまで来た瞬間にジャストガードすると弾き返せ、跳ね返った火球がドラゴンに当たるとダメージと撃ち落としのたまり＝3発ほどで墜落。fireball の reflect-*）、急降下（高く昇って輪で狙う→赤く固定→突っ込んで着地の衝撃→低く浮いて立て直す＝殴りどき）、尻尾の薙ぎ払い（低く降りて一回転。尻尾のボーンを SwordSweep で判定。真下は安全）、翼の突風（近くの相手を吹き飛ばす）。撃ち落とし（高い所で受けたダメージが最大 HP の knockdown-ratio たまると落ちて動けない）。HP 50% で本気（rage_shift：翼で包んで溜め→吠えて目と体の炎が赤く・翼から火の粉）→ 技が強化（_rage：ブレス往復・火球5発・急降下2連・尻尾2回転・熱風）＋業火の雨（高く昇って火球を降らせ続け、その後疲れ果てる）。死ぬと空中で灰になって崩れる。アニメは1つずつしか再生されないので、どのアニメにも羽ばたきを入れてある（下地は hover / fly のループ）。モデルは run/model_backup/build_dragon.py で一から生成（ガンナーと同じ決まり。render_anim.py に center=0,9,5 span=95 を付けると全体が入る） |
| `giant` | 大巨人 | `giant` | 苔むした丸石 | 約8.5ブロックの岩の巨人（model-scale 4.2 / entity-scale 4.3）。石の大棍棒を引きずり、AI を切ってゆっくり歩く（足音で地面が揺れる。向きを変えるのが遅い）。技は「動き」（yml の moves。GiantMove）を並べた連携（GiantComboSkill）。盾は一撃で壊れる（shield.break-damage 1。ジャストガードなら壊れない）。どの動きも溜めが長く、範囲がとても広い（棍棒の先より広め）。地面に範囲の予告（中を色が満たし切った瞬間に当たる）。範囲は CIRCLE（叩きつけ・踏みつけ）/ CONE（薙ぎ払い・返し）/ LINE（大地割り：手前から奥へ割れる）/ THROW（岩投げ：投げた瞬間に着く所が固まる）/ LEAP（大跳躍：跳んだ瞬間に着地点が固まる）。ブロックを積んで登った相手対策: どの動きも上へ hit-height までの柱で当たる（予告は相手の高さにも出る）。それより高い相手には岩投げが優先して直接その高さへ投げる（狙う高さは extra.air-target-height＝Boss.targetMaxHeight。target.max-height は使わない）。連携の最後に片膝をついて肩で息をする長い隙（被ダメ増加）。HP 50% で本気（roar：目と胸の紋様 glow_runes が赤く燃え、火の粉。体がみるみる rage-size 倍まで大きくなり＝範囲も同じだけ広がる、動きがワンテンポ速くなる＝rage-tempo で時間とアニメの速さ（GiantMove.scaled）。歩きも速い）→ 連携の長さがランダム（moves で始まり chain-pool から足して chain-min〜chain-max 個）・大地割りが加わる。死ぬと膝をついて前へ倒れ込む。モデルは run/model_backup/build_giant.py で一から生成（ガンナーと同じ決まり。範囲は棍棒の先の位置を FK で計算したものより広げてある） |
| `assassin` | 影の暗殺者 | `assassin` | エコーの欠片 | 黒装束に覆面・逆手の二刀。歩かずに小刻みなステップで動く（AI を切って自分でテレポート。遠ければジグザグに詰め、近すぎれば跳び退き、ほどよい距離なら横へ刻む。extra.step-*）。見切り（技の合間に殴られると dodge-chance ですり抜け、残像を残して跳び退いて苦無を投げ返す。技の最中・後の隙は必ず当たる）。連撃（BoneStrikeSkill・前ステップで詰めて1振りごとに踏み込む3連撃）、苦無（扇状。BulletField）、影縫い（足元に苦無→刺さった輪の中だとスタン→連撃で追い打ち）、影渡り（沈んで影になり背後へ這い寄る＝ isIntangible で無敵→予告の輪と鼓動→飛び出して斬り上げ→隙）。光に弱い（影が extra.light-expose-level 以上のブロックの光に入ると引きずり出されてよろめく。明るい所では潜れない）。HP 50% で本気（rage_shift：目が赤く、刃に紫の光 glow_edge_*）→ 技が強化（5連撃・苦無5本×2・影縫い3本・見切りで3本）＋影渡りに分身（偽物の影は先に弾ける。本物の影にだけ目の光）＋影舞（予告の線に沿った一閃×5→跳び上がって突き下ろし→疲れ果てる）。分身（AssassinClone＝DummyTracker＋殴れる Interaction。殴ると煙になって消える）: 残像の連撃（本気。連撃に合わせて左右から分身が少し遅れて斬りかかる）、分身の苦無（本気。標的をはさんで左右に分身→3方向から一斉に扇で投げる。直前に通り道の線）、HP 25% で瀕死→分身の囲み（印を結んで煙に消え、4体で円になって囲み小刻みに回る。本物の足元にだけ影。本物にダメージで見破り＝分身が消えてよろめく／時間切れで全員が順に線に沿って突進、最後が本物）。死ぬと膝をつき影に溶けて沈む。体を隠すのはルートボーン assassin の表示切り替え。モデルは run/model_backup/build_assassin.py で一から生成（ガンナーと同じ決まり。grounded() で足の裏を地面に合わせる） |
| `tutorial_alpha` | チュートリアルアルファ | `tutorial_alpha` | ドラゴンブレス | 最初のボス。戦い方を教える作りで、強さは控えめ（HP 500・damage-multiplier 3・技の間の隙 16tick）。突進（DashSkill。溜めの間に赤い線の予告）、剣振り・ジャブ・溜め斬り（BoneStrikeSkill）、跳躍（LeapSkill・専用。桃色の輪の予告）。技を初めて使うとき避け方をチャットで教える（技の yml の hint。extra.hints）。召喚直後の最初の教え（skills.resource_pack）でリソースパックの直し方（/resourcepack setting でアドオンを ON → /resourcepack apply。別プラグイン ResourcePackFlow のコマンド）を伝え、その間は止まる（無敵・寿命とクリアタイムも進まない＝Boss.isPaused）。その後に登場の挨拶。登場の後はジャストガードの練習（extra.lesson）: 誰かがジャストガードに成功するまで、合図付きの素振り（lesson_swing）だけを繰り返してダメージを受けず、殴られたら叱り、素振りを受け損ねたら「盾が無い／早い／遅い」を見分けて叱る（ほかの技は AfterLesson で練習の後だけ）。練習が終わるとすぐ花びらの玉（PetalOrbSkill・専用。ゆっくり飛ぶ玉で、ジャストガードで弾き返すとアルファに当たってよろめく）を投げ、弾き返しを教える（初めて弾き返されたら、ドラゴンの火球も弾き返せると教える）。登場の挨拶、HP 50% で少しだけ本気（ジャブの後にときどき溜め斬りを続ける＝連携。extra.chain-chance）。モデルは手作り（生成スクリプトなし）。プレイヤーを倒すと消える（コマンド召喚時） |
| `knight` | 髑髏騎士 | `knight` | ウィザースケルトンの頭 | ぼろぼろの赤いマントを引きずる、人の3倍ほどの背の骸骨の騎士（model-scale 1.8 / entity-scale 2.7）。身の丈を超える大剣を両手で振るう（BoneStrikeSkill・greatsword を BLADE で判定）。頭巾はかぶらず、小さめの黒い頭骨が細く長い首で前を向く（頭は HEAD_SCALE で縮めて置く）。攻撃はスタイリッシュな動き（薙ぎ払い＝背を向けるほどひねってからの回転斬り・突き＝目の高さで水平に構えて低く長く踏み込む・振り下ろし＝しゃがむ→跳び上がって頭上へ→片膝をついて着地しながら叩きつける・溜め斬り＝居合。剣の向きは生成スクリプトの point_sword で決める。刃の面も振る向きに合わせる＝横に払う技は寝かせ、振り下ろしは立てる）。技の合間は相手の周りを回って間合いを測る（KnightCircleSkill：技の終わりに extra.circle-chance の割合。大剣を構えたすり足 strafe_left/right で横へ回り、ときどき素早い横ステップ step_left/right）。刃はどのアニメでも地面に埋まらない（生成スクリプトの keep_blade_above。大剣にすがるポーズは切っ先を前の地面に着ける）。どの技も振りかぶりながら下がり（retreat-windows）、振りながら大きく踏み込む（居合の溜め斬りは標的を突き抜けて駆け抜ける）で、モデルごと踏み込む（アニメのルート位置）。当たりやすさ優先で、刃の判定は太く（min-blade-radius 1.0）判定時間も長い（走って避けるのはほぼ無理＝ジャストガードで受ける）。歩きは AI（idle / walk）。浮遊（KnightFloatSkill：跳躍斬り以外の技は float-chance の割合で、斜め後ろへ下がりながら宙に浮き上がり（float-back-*。開けている側へ）、滑るように寄って、降りながら打つ。浮き上がる float_rise・宙を滑って寄る float_glide・寄り終えて待つ float と宙からの技 slash_air / thrust_air / slam_air / charge_slash_air。真下の地面に影。頭の上が狭いと地上のまま。連撃は1つ目だけ宙から。float-height と降りる tick はアニメに合わせてある）。薙ぎ払い（踏み込んでから振る）、突き（遠くから一気に踏み込む）、振り下ろし（前へ地割れが走る。KnightHazards）、溜め斬り（基本の技。charge_slash：深くひねって大剣を右後ろへ引き、震えながら溜める＝charge-windows の間、大剣に赤い光が集まる→大きく薙ぎ払う）、連携（RandomChainSkill・共通：溜め斬りで始め、chain-pool から技をランダムに足して chain-min〜chain-max＝3〜4回続け、最後に finisher の疲れ tired＝大剣にもたれて肩で息をする殴りどき）、跳躍斬り（KnightLeapSkill：離れた相手の所へ跳んで叩きつける。予告の輪が着地の lock-ticks 前に固まる）。打ち上げ（KnightLaunchSkill：斬り上げが当たった相手は、ガード・ジャストガードしても約20ブロック上へ飛ぶ＝吹き飛ばし止めが切れる 2tick 後に飛ばす。騎士も跳んで追いつき、相手をテレポートで宙に留めて空中連撃 air_combo → air_finish で叩き落とす。落下ダメージは status.fall で防ぐ）。溜め刺し（KnightImpaleSkill：長く溜めて長く踏み込む突き。刃の線に当たった最初の1人が刺さり、毎tick 刃の blade-point の所へ固定 → impale_swing で掲げて一回転し、前へ投げる。ジャストガードすれば刺さらない。刃の位置は KnightBoss.bladeSegment / bladePoint）。怨霊（KnightWraithSkill：離れているとき。体から左右へ弧を描いて飛び出し、turn-degrees ずつ向きを変えてゆっくり追う魂の弾。ブロックで消える）。雷（KnightLightningSkill：離れているとき。大剣を天へ掲げて呼び続け、相手のまわりのランダムな所に予告の輪 → 落雷＝strikeLightningEffect＋自前のダメージ）。大剣の技は yml の crack-tick / crescent-tick で地割れ・斬撃波を足せる（KnightStrike）。体勢崩し（ジャストガードを extra.guard-break-count 回受けるとよろめく＝被ダメ増加）。HP 50% で本気（rage_shift：天を仰いで吠える。目が赤く、大剣が赤熱＝glow_edge）→ 溜め斬りと薙ぎ払いが深紅の斬撃波を放つ・地割れが3本・跳躍斬りが2連続・歩きが速い。瀕死（KnightLastStandSkill：致命の一撃を HP 1 で耐える＝interceptDamage → 全員をノーダメージで約28ブロック先へ吹き飛ばす → 倒されるまで全員へ怨霊の束＝KnightHazards.volley を放ち続ける。束は必ず追いつき、届く直前に本人へ鐘の合図。ジャストガードしないと普通に防いでも押し戻される。吹き飛ばしの後の近接の一撃だけが効く＝飛び道具・とげ・燃焼などは無効）。プレイヤーを倒すと首をかしげて剣先を向ける。死ぬと両膝をついて大剣にすがり、灰になって崩れる。モデルは run/model_backup/build_knight.py で一から生成（マントのボーンは mantle_1〜3・左右の mantle_r_* / mantle_l_*。cape で始めると表示されない。ガンナーと同じ決まり。マントは drape() が地面に合わせて垂らす。攻撃中のなびきは FLUTTER 表に、アニメ名とキーの時刻で書く） |