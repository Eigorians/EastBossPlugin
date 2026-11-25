package com.eastcompany.eastsub.eastBossPlugin

import org.bukkit.NamespacedKey

/**
 * プラグイン内で使う「名前」を一か所にまとめたもの。
 * コマンド名・権限・設定キー・ファイル名・PDC キーなどの文字列を直書きせず、必ずここを参照する。
 */
object Names {

    /** NamespacedKey などに使う名前空間 */
    const val NAMESPACE = "eastboss"

    /** コマンド名 */
    object Command {
        const val ROOT = "eastboss"
        val ALIASES = listOf("eb")

        const val RELOAD = "reload"
        const val LOCATION = "location"
        const val TRADER = "trader"
        const val BOSS = "boss"
        const val DEBUG = "debug"

        const val SET = "set"
        const val INFO = "info"
        const val TP = "tp"
        const val SPAWN = "spawn"
        const val REMOVE = "remove"
        const val SUMMON = "summon"
        const val LIST = "list"
        const val RANKING = "ranking"
        const val BATTLE = "battle"
        const val CANCEL = "cancel"
        const val RECORD = "record"
        const val RESET = "reset"
        const val CONFIG = "config"
        const val PROGRESS = "progress"
        const val GRANT = "grant"
        const val REVOKE = "revoke"
        /** progress grant / revoke で「全部」 */
        const val ALL = "all"

        // 引数名
        const val ARG_OFFSET_X = "offsetX"
        const val ARG_OFFSET_Y = "offsetY"
        const val ARG_OFFSET_Z = "offsetZ"
        const val ARG_BOSS_ID = "id"
        const val ARG_FILE = "file"
        const val ARG_KEY = "key"
        const val ARG_VALUE = "value"
        const val ARG_PLAYER = "player"
        /** /eb config で config.yml を指す名前（ほかはボスID） */
        const val MAIN_CONFIG = "config"
    }

    /** 権限ノード（paper-plugin.yml の permissions と揃える） */
    object Permission {
        const val ADMIN = "eastboss.admin"
        /** ボスの解放の順番に関係なく、どのボスでも戦いを始められる */
        const val PROGRESS_BYPASS = "eastboss.progress.bypass"
    }

    /** データフォルダ内のファイル名 */
    object File {
        const val CONFIG = "config.yml"
        const val LOCATIONS = "locations.yml"
        const val RECORDS = "records.yml"
        /** プレイヤーごとの、倒したボス（ボスの解放） */
        const val PROGRESS = "progress.yml"

        /** ボスごとの設定ファイルを置くフォルダ（bosses/<ボスID>.yml） */
        const val BOSS_DIR = "bosses"

        fun boss(id: String) = "$BOSS_DIR/$id.yml"
    }

    /** config.yml のキー */
    object Config {
        const val PREFIX = "prefix"
        const val DEBUG_ENABLED = "debug.enabled"
        const val DEBUG_SHOW_LOCATION = "debug.show-location"
        const val DEBUG_SHOW_HITBOX = "debug.show-hitbox"
        const val TRADER_NAME = "trader.name"
        const val LOCATION_YAW_SNAP = "location.yaw-snap"
        const val LOCATION_RESET_PITCH = "location.reset-pitch"
        const val STATUS_STUN_SLOW_AMPLIFIER = "status.stun.slow-amplifier"
        const val STATUS_HEAL_LOCK_TICKS = "status.heal-lock-ticks"
        /** リスポーンしてから、ボスに狙われず攻撃も当たらない時間（tick） */
        const val STATUS_RESPAWN_PROTECTION_TICKS = "status.respawn-protection-ticks"
        /** ジャストガードの受付の長さ（盾を構えてからこの tick 以内に届いた攻撃をジャストガードにする） */
        const val JUST_GUARD_WINDOW_TICKS = "just-guard.window-ticks"
        /** 盾を構えてから、構え直しても受付が開かない時間（連打の防止） */
        const val JUST_GUARD_LOCKOUT_TICKS = "just-guard.lockout-ticks"
        /** ジャストガードできるのは、ボスの方をこの角度以内で向いているときだけ（度。盾の正面） */
        const val JUST_GUARD_FACING_DEGREES = "just-guard.facing-degrees"
        const val BOSS_MAX_DAMAGE_PER_HIT = "boss.max-damage-per-hit"
        const val BOSS_PROJECTILE_DAMAGE_MULTIPLIER = "boss.projectile-damage-multiplier"
        const val BATTLE_NOTIFY_RADIUS = "battle.notify-radius"
        const val BATTLE_DEATH_LIMIT = "battle.death-limit"
        const val BATTLE_COUNTDOWN_SECONDS = "battle.countdown-seconds"
        const val BATTLE_DISPLAY_HEIGHT = "battle.display-height"
        const val BATTLE_COUNTDOWN_TITLE = "battle.countdown-title"
        const val BATTLE_LEAVE_RADIUS = "battle.leave-radius"
        /** 参加者の死亡ログ・倒れた回数を、参加者のほかにボスのこの距離以内の人にも見せる（0 で参加者だけ） */
        const val BATTLE_DEATH_LOG_RADIUS = "battle.death-log-radius"
        const val BATTLE_BOSSBAR_TIMER = "battle.bossbar-timer"
        const val BATTLE_ABSENT_SECONDS = "battle.absent-seconds"
        const val RECORD_RANKING_SIZE = "record.ranking-size"
        const val MENU_TITLE = "menu.title"
        const val PROGRESSION_ENABLED = "progression.enabled"
        const val BETTERMODEL_RELOAD_AFTER_SERVER_RELOAD = "bettermodel.reload-after-server-reload"
        const val BETTERMODEL_RELOAD_ON_STARTUP = "bettermodel.reload-on-startup"
        const val BETTERMODEL_RELOAD_DELAY_TICKS = "bettermodel.reload-delay-ticks"
        const val BETTERMODEL_RELOAD_COMMAND = "bettermodel.reload-command"
        const val PROGRESSION_ORDER = "progression.order"
        const val PROGRESSION_HIDDEN_NAME = "progression.hidden-name"
        const val PROGRESSION_HIDDEN_ICON = "progression.hidden-icon"

        /** messages セクションのキーを組み立てる */
        fun message(key: String) = "messages.$key"
    }

    /** ボス設定ファイル（bosses/<ボスID>.yml）の共通キー */
    object BossConfig {
        const val NAME = "name"
        const val MODEL = "model"
        const val MODEL_SCALE = "model-scale"
        const val ENTITY_SCALE = "entity-scale"
        const val DAMAGE_MULTIPLIER = "damage-multiplier"
        const val MAX_HEALTH = "max-health"
        const val HEALTH_PER_PLAYER = "health-per-player"
        const val LIFETIME_TICKS = "lifetime-ticks"
        /** 技が終わってから次の技を出すまで、何もしない時間（tick。殴りどき） */
        const val SKILL_GAP_TICKS = "skill-gap-ticks"

        const val TARGET_SEARCH_RANGE = "target.search-range"
        const val TARGET_MAX_HEIGHT = "target.max-height"

        const val BOSSBAR_COLOR = "bossbar.color"
        const val BOSSBAR_OVERLAY = "bossbar.overlay"
        const val BOSSBAR_RANGE = "bossbar.range"

        const val DEATH_ANIMATION = "death.animation"
        const val DEATH_TICKS = "death.ticks"

        const val IMMUNE_PROJECTILE = "immunity.projectile"
        const val IMMUNE_POTION = "immunity.potion"
        const val IMMUNE_VERTICAL_KNOCKBACK = "immunity.vertical-knockback"
        /** 飛び道具で受けるダメージの倍率（書かなければ config.yml の boss.projectile-damage-multiplier） */
        const val PROJECTILE_DAMAGE_MULTIPLIER = "immunity.projectile-damage-multiplier"
        /** 盾で防いだダメージがこの量たまると盾が壊れる（0 以下で壊れない） */
        const val SHIELD_BREAK_DAMAGE = "shield.break-damage"
        /** 盾が壊れた後、使えない時間（tick） */
        const val SHIELD_DISABLE_TICKS = "shield.disable-ticks"
        /** 技ごとに、盾で防いだときにたまる量の倍率（技ID: 倍率。書いていない技は 1） */
        const val SHIELD_SKILL_MULTIPLIERS = "shield.skill-multipliers"

        /** ボス固有の値を置くセクション */
        const val EXTRA = "extra"
        /** 第2フェーズ（本気）に移る HP の割合 */
        const val RAGE_HEALTH_RATIO = "extra.rage-health-ratio"
        /** バイザー（目）の色 "#RRGGBB"。通常時と本気のとき */
        const val VISOR_COLOR = "extra.visor-color"
        const val VISOR_RAGE_COLOR = "extra.visor-rage-color"
        /** ガンナー: 拳銃の弾数（左右合わせて）。撃ち尽くすとリロードする */
        const val AMMO = "extra.ammo"
        const val RAGE_AMMO = "extra.rage-ammo"
        /** ガンナー: この HP の割合以下で瀕死（決闘を使う） */
        const val FINAL_HEALTH_RATIO = "extra.final-health-ratio"
        /** ガンナー: 召喚地点からこれ以上離れると、技の移動が召喚地点の方へ寄る */
        const val HOME_RADIUS = "extra.home-radius"
        /** ベイガー・ドラゴン: 本気モードの目の色 */
        const val RAGE_EYE_COLOR = "extra.rage-eye-color"
        /** ドラゴン: 技の合間に飛び回る高さ（地面から）・速さ（ブロック/tick）・回る半径 */
        const val CRUISE_HEIGHT = "extra.cruise-height"
        const val CRUISE_SPEED = "extra.cruise-speed"
        const val ORBIT_RADIUS = "extra.orbit-radius"
        /** ドラゴン: 登場のとき、召喚地点のどれだけ上から降りてくるか */
        const val SPAWN_HEIGHT = "extra.spawn-height"
        /** ドラゴン: 本気モードの体の炎の色 */
        const val RAGE_CORE_COLOR = "extra.rage-core-color"
        /** ドラゴン: 撃ち落とし。高い所で受けたダメージが最大 HP のこの割合たまると落ちる */
        const val KNOCKDOWN_RATIO = "extra.knockdown-ratio"
        /** ドラゴン: 地面からこの高さ以上にいるときに受けたダメージだけをためる */
        const val KNOCKDOWN_MIN_HEIGHT = "extra.knockdown-min-height"
        /** ドラゴン: 撃ち落とされてから、次に撃ち落とせるようになるまで（tick） */
        const val KNOCKDOWN_COOLDOWN_TICKS = "extra.knockdown-cooldown-ticks"

        // ---- 暗殺者: 技の合間の小刻みなステップ ----
        /** 1回のステップで進む距離（最小・最大。ブロック） */
        const val STEP_DISTANCE_MIN = "extra.step-distance-min"
        const val STEP_DISTANCE_MAX = "extra.step-distance-max"
        /** 1回のステップにかける tick */
        const val STEP_TICKS = "extra.step-ticks"
        /** ステップとステップの間に止まる tick（最小・最大） */
        const val STEP_PAUSE_MIN = "extra.step-pause-min"
        const val STEP_PAUSE_MAX = "extra.step-pause-max"
        /** この距離を保つように、標的のまわりを横へ刻む */
        const val PREFERRED_DISTANCE = "extra.preferred-distance"
        /** 標的がこれより遠いと、ジグザグに詰め寄る */
        const val CLOSE_IN_DISTANCE = "extra.close-in-distance"
        /** 標的がこれより近いと、斜め後ろへ跳び退く */
        const val BACK_OFF_DISTANCE = "extra.back-off-distance"
        // ---- 暗殺者: 見切り・影・目 ----
        const val DODGE_CHANCE = "extra.dodge-chance"
        const val DODGE_COOLDOWN_TICKS = "extra.dodge-cooldown-ticks"
        const val DODGE_MESSAGE = "extra.dodge-message"
        /** 影の位置のブロックの明るさ（ブロックの光だけ）がこれ以上だと、影から引きずり出される */
        const val LIGHT_EXPOSE_LEVEL = "extra.light-expose-level"
        const val EYE_COLOR = "extra.eye-color"
        // ---- チュートリアルアルファ: 戦い方を教える ----
        /** 技を初めて使うとき、避け方をチャットで教えるか */
        const val HINTS = "extra.hints"
        /** 同じ技の説明を毎回出すか */
        const val HINT_REPEAT = "extra.hint-repeat"
        /** 説明の頭に付ける文字 */
        const val HINT_PREFIX = "extra.hint-prefix"
        /** 少しだけ本気になってから、ジャブの後に溜め斬りを続ける確率 */
        const val CHAIN_CHANCE = "extra.chain-chance"
        /** ジャストガードの練習（成功するまで、わかりやすい素振りだけを繰り返し、ダメージを受けない） */
        const val LESSON_ENABLED = "extra.lesson.enabled"
        /** 叱る間隔（同じ人を、攻撃されるたびに叱りすぎないように。tick） */
        const val LESSON_SCOLD_COOLDOWN_TICKS = "extra.lesson.scold-cooldown-ticks"
        const val LESSON_PREFIX = "extra.lesson.prefix"
        /** 練習中に殴ってきた人を叱る言葉（どれか1つ） */
        const val LESSON_SCOLD_ATTACK = "extra.lesson.scold-attack"
        /** 練習の素振りが当たったとき: 盾を持っていない */
        const val LESSON_SCOLD_NO_SHIELD = "extra.lesson.scold-no-shield"
        /** 練習の素振りが当たったとき: 構えるのが早すぎる（構えっぱなし） */
        const val LESSON_SCOLD_EARLY = "extra.lesson.scold-early"
        /** 練習の素振りが当たったとき: 構えるのが遅い（構えていない） */
        const val LESSON_SCOLD_LATE = "extra.lesson.scold-late"
        /** ジャストガードに成功したとき（参加者みんなに） */
        const val LESSON_PASS = "extra.lesson.pass"
        /** 素振りの合図（アクションバー）と、それを出す tick（振り始めから） */
        const val LESSON_COUNTDOWN = "extra.lesson.countdown"
        const val LESSON_COUNTDOWN_TICKS = "extra.lesson.countdown-ticks"
        // ---- 大巨人 ----
        /** 動き（連携の部品）の定義（トップレベルの moves セクション） */
        const val MOVES = "moves"
        const val WALK_SPEED = "extra.walk-speed"
        const val WALK_TURN_SPEED = "extra.walk-turn-speed"
        const val STOP_DISTANCE = "extra.stop-distance"
        // ---- 焔竜: チャーミングな振る舞い ----
        /** 旋回中のくしゃみの間隔（この間でランダム。tick） */
        const val SNEEZE_MIN_TICKS = "extra.sneeze-min-ticks"
        const val SNEEZE_MAX_TICKS = "extra.sneeze-max-ticks"
        const val RUNE_COLOR = "extra.rune-color"
        const val RAGE_RUNE_COLOR = "extra.rage-rune-color"
        /** 本気になると体がこの倍率まで大きくなる（範囲も同じだけ広がる） */
        const val RAGE_SIZE = "extra.rage-size"
        /** 本気の動きの時間の倍率（0.8 なら8割の時間＝ワンテンポ速い。アニメも速く流す） */
        const val RAGE_TEMPO = "extra.rage-tempo"
        /** 本気になって大きくなるのにかける時間（tick） */
        const val RAGE_GROW_TICKS = "extra.rage-grow-ticks"
        /** 足元よりこれ以上高い相手は「高い所」（予告をその高さにも出す・岩は相手の高さへ直接投げる） */
        const val AIR_HEIGHT = "extra.air-height"
        /** この高さの相手まで狙う（target.max-height の代わり） */
        const val AIR_TARGET_HEIGHT = "extra.air-target-height"
        // ---- 髑髏騎士 ----
        /** 歩く速さ（エンティティの移動速度。ハスクは 0.23） */
        const val MOVE_SPEED = "extra.move-speed"
        /** 本気になってから歩く速さ */
        const val RAGE_MOVE_SPEED = "extra.rage-move-speed"
        /** 体勢崩し: ジャストガードをこの回数受けると、よろめいて大きな隙をさらす */
        const val GUARD_BREAK_COUNT = "extra.guard-break-count"
        /** 体勢崩し: 最後にジャストガードされてからこの tick たつと、数え直し */
        const val GUARD_BREAK_RESET_TICKS = "extra.guard-break-reset-ticks"
        /** 体勢崩し: 崩したときに、ジャストガードした人へ出す文（アクションバー） */
        const val GUARD_BREAK_MESSAGE = "extra.guard-break-message"
        /** 髑髏騎士: 技が終わった後に、相手の周りを回って間合いを測る割合（0〜1） */
        const val CIRCLE_CHANCE = "extra.circle-chance"
        /** 髑髏騎士: 瀕死（HP 1 で耐える）を使うか・始まったときの題字・殴っても効かないときの知らせ */
        const val LAST_STAND_ENABLED = "extra.last-stand.enabled"
        const val LAST_STAND_TITLE = "extra.last-stand.title"
        const val LAST_STAND_SUBTITLE = "extra.last-stand.subtitle"
        const val LAST_STAND_BLOCKED_MESSAGE = "extra.last-stand.blocked-message"

        /** 技ごとの設定セクション */
        fun skill(id: String) = "skills.$id"
    }

    /** 技の設定セクション（skills.<技ID>）内のキー */
    object SkillConfig {
        // ---- 共通 ----
        const val WEIGHT = "weight"
        const val ANIMATION = "animation"
        const val RANGE = "range"
        const val DAMAGE = "damage"
        const val END_TICKS = "end-ticks"

        // ---- ボーン判定（BoneStrikeSkill / LeapSkill） ----
        const val BONES = "bones"
        const val SHAPE = "shape"
        const val HIT_WINDOWS = "hit-windows"
        const val MIN_BLADE_RADIUS = "min-blade-radius"
        // 振る前に標的へダッシュで近づく（BoneStrikeSkill）
        const val APPROACH_SPEED = "approach-speed"
        const val APPROACH_DISTANCE = "approach-distance"
        const val APPROACH_MAX_TICKS = "approach-max-ticks"
        const val APPROACH_ANIMATION = "approach-animation"

        // ---- 突進（DashSkill） ----
        const val WINDUP_TICKS = "windup-ticks"
        const val HIT_END_TICKS = "hit-end-ticks"
        const val SPEED = "speed"
        const val HIT_RADIUS = "hit-radius"
        const val STUN_TICKS = "stun-ticks"

        // ---- 跳躍（LeapSkill） ----
        const val JUMP_START_TICKS = "jump-start-ticks"
        const val PEAK_TICKS = "peak-ticks"
        const val LAND_TICKS = "land-ticks"
        const val SLIDE_END_TICKS = "slide-end-ticks"
        const val JUMP_HEIGHT = "jump-height"
        const val DIVE_TICKS = "dive-ticks"
        const val LOCK_TICKS = "lock-ticks"
        const val LEAD_DISTANCE = "lead-distance"
        const val MOVING_THRESHOLD = "moving-threshold"
        const val FALLBACK_DISTANCE = "fallback-distance"
        const val SLIDE_SPEED = "slide-speed"
        const val TAKEN_DAMAGE = "taken-damage"
        const val IMPACT_RADIUS = "impact-radius"
        const val IMPACT_DAMAGE = "impact-damage"

        // ---- 隕石（MeteorShowerSkill） ----
        const val CAST_START_TICKS = "cast-start-ticks"
        const val CAST_END_TICKS = "cast-end-ticks"
        const val INTERVAL_TICKS = "interval-ticks"
        const val METEORS_PER_WAVE = "meteors-per-wave"
        const val AREA_RADIUS = "area-radius"
        const val AIM_RATIO = "aim-ratio"
        const val AIM_COOLDOWN_TICKS = "aim-cooldown-ticks"
        const val WARNING_TICKS = "warning-ticks"
        const val FALL_HEIGHT = "fall-height"
        const val FIRE_TICKS = "fire-ticks"
        const val KNOCKBACK = "knockback"
        const val METEOR_BLOCK = "meteor-block"
        const val CAST_DAMAGE_MULTIPLIER = "cast-damage-multiplier"
        const val EXHAUSTED_START_ANIMATION = "exhausted-start-animation"
        const val EXHAUSTED_START_TICKS = "exhausted-start-ticks"
        const val EXHAUSTED_ANIMATION = "exhausted-animation"
        const val EXHAUSTED_TICKS = "exhausted-ticks"
        const val EXHAUSTED_END_ANIMATION = "exhausted-end-animation"
        const val EXHAUSTED_END_TICKS = "exhausted-end-ticks"
        const val EXHAUSTED_DAMAGE_MULTIPLIER = "exhausted-damage-multiplier"

        // ---- 衝撃波（GroundShockwaveSkill） ----
        const val IMPACT_TICKS = "impact-ticks"
        const val WAVES = "waves"
        const val WAVE_INTERVAL_TICKS = "wave-interval-ticks"
        const val WAVE_SPEED = "wave-speed"
        const val START_RADIUS = "start-radius"
        const val MAX_RADIUS = "max-radius"
        const val WAVE_WIDTH = "wave-width"
        const val JUMP_CLEARANCE = "jump-clearance"
        const val LAUNCH = "launch"

        // ---- 魔法の弾（MagicBoltSkill） ----
        const val SHOOT_TICKS = "shoot-ticks"
        const val COUNT = "count"
        const val SPREAD_DEGREES = "spread-degrees"
        const val HOMING = "homing"
        const val MAX_LIFE_TICKS = "max-life-ticks"
        const val EXPLOSION_RADIUS = "explosion-radius"
        const val LAUNCH_HEIGHT = "launch-height"
        const val LAUNCH_FORWARD = "launch-forward"

        // ---- 回転ビーム（RotatingBeamSkill） ----
        const val FIRE_START_TICKS = "fire-start-ticks"
        const val FIRE_END_TICKS = "fire-end-ticks"
        const val ROTATION_DEGREES = "rotation-degrees"
        const val LENGTH = "length"
        const val ORIGIN_FORWARD = "origin-forward"
        const val ORIGIN_HEIGHT = "origin-height"
        const val DROP_DISTANCE = "drop-distance"
        const val BEAM_HEIGHT = "beam-height"
        const val HIT_HEIGHT = "hit-height"
        const val HIT_WIDTH = "hit-width"
        const val HIT_INTERVAL_TICKS = "hit-interval-ticks"

        // ---- 演出（PerformSkill） ----
        /** 最中に受けるダメージの倍率 */
        const val TAKEN_DAMAGE_MULTIPLIER = "taken-damage-multiplier"

        // ---- 弾（BulletField。技の bullet セクション） ----
        const val BULLET = "bullet"
        const val MAX_RANGE = "max-range"
        const val IGNORE_INVULNERABILITY = "ignore-invulnerability"
        const val COLOR = "color"
        const val SIZE = "size"
        /** 弾がブロックで跳ね返る回数 */
        const val BOUNCES = "bounces"

        // ---- ガンナーの技 ----
        /** この距離より近い標的には使わない */
        const val MIN_RANGE = "min-range"
        const val DURATION_TICKS = "duration-ticks"
        const val PREFERRED_DISTANCE = "preferred-distance"
        const val SHOOT_INTERVAL_TICKS = "shoot-interval-ticks"
        const val FIRST_SHOT_TICK = "first-shot-tick"
        const val SWAP_TICK = "swap-tick"
        const val DRAW_ANIMATION = "draw-animation"
        const val DRAW_TICKS = "draw-ticks"
        const val AIM_ANIMATION = "aim-animation"
        const val AIM_TICKS = "aim-ticks"
        const val NEXT_AIM_TICKS = "next-aim-ticks"
        const val FIRE_ANIMATION = "fire-animation"
        const val FIRE_ANIMATION_TICKS = "fire-animation-ticks"
        const val STOW_ANIMATION = "stow-animation"
        const val STOW_TICKS = "stow-ticks"
        const val SHOTS = "shots"
        const val TURN_SPEED = "turn-speed"
        const val TURN_ACCELERATION = "turn-acceleration"
        const val MAX_TURN_SPEED = "max-turn-speed"
        const val HIT_SLOW_TICKS = "hit-slow-ticks"
        const val HIT_SLOW_AMPLIFIER = "hit-slow-amplifier"
        const val RETARGET_TICKS = "retarget-ticks"
        const val AIM_DAMAGE_MULTIPLIER = "aim-damage-multiplier"
        const val GRAB_TICK = "grab-tick"
        const val THROW_TICK = "throw-tick"
        const val RETURN_TICK = "return-tick"
        const val SPREAD_RADIUS = "spread-radius"
        const val FLIGHT_TICKS = "flight-ticks"
        const val ARC_HEIGHT = "arc-height"
        const val FUSE_TICKS = "fuse-ticks"
        const val GRENADE_BLOCK = "grenade-block"
        const val MOVE_START_TICK = "move-start-tick"
        const val MOVE_END_TICK = "move-end-tick"
        const val DISTANCE = "distance"
        const val SIDE_ANGLE = "side-angle"
        const val START_ANIMATION = "start-animation"
        const val START_TICKS = "start-ticks"
        const val FIRE_DURATION_TICKS = "fire-duration-ticks"
        const val OVERHEAT_ANIMATION = "overheat-animation"
        const val OVERHEAT_TICKS = "overheat-ticks"
        const val COOLDOWN_ANIMATION = "cooldown-animation"
        const val COOLDOWN_TICKS = "cooldown-ticks"
        const val END_ANIMATION = "end-animation"
        const val END_ANIMATION_TICKS = "end-animation-ticks"
        const val END_SWAP_TICK = "end-swap-tick"
        const val OVERHEAT_DAMAGE_MULTIPLIER = "overheat-damage-multiplier"
        const val VISOR_CHANGE_TICK = "visor-change-tick"

        // ガンナー: リロード・挑発
        const val EJECT_TICK = "eject-tick"
        const val INSERT_TICK = "insert-tick"
        const val CLOSE_TICK = "close-tick"
        const val SMOKE_TICK = "smoke-tick"
        const val CORNERED_WEIGHT_MULTIPLIER = "cornered-weight-multiplier"

        // ベイガー: 本気モード・魔法陣の檻
        const val CHANGE_TICK = "change-tick"
        const val ACTIVATE_TICK = "activate-tick"

        // ガンナー: 決闘
        const val READY_ANIMATION = "ready-animation"
        const val READY_TICKS = "ready-ticks"
        const val COIN_TICK = "coin-tick"
        const val COIN_HEIGHT = "coin-height"
        const val WAIT_ANIMATION = "wait-animation"
        const val WAIT_TICKS = "wait-ticks"
        const val BELLS = "bells"
        /** 決闘: 撃つ何 tick 前に、撃たれる人へ合図を出すか */
        const val CUE_LEAD_TICKS = "cue-lead-ticks"
        const val WARN_TITLE = "warn-title"
        const val WARN_SUBTITLE = "warn-subtitle"
        const val SEEN_MESSAGE = "seen-message"
        const val CUE_MESSAGE = "cue-message"
        /** 盾を貫く（ジャストガードも、ふつうのガードも効かない） */
        const val PIERCE_SHIELD = "pierce-shield"
        /** 弾き返し（ジャストガードで弾を跳ね返す） */
        const val REFLECTABLE = "reflectable"
        const val REFLECT_RADIUS = "reflect-radius"
        const val REFLECT_SPEED = "reflect-speed"
        const val REFLECT_DAMAGE = "reflect-damage"
        const val REFLECT_KNOCKDOWN = "reflect-knockdown"

        // ---- ドラゴンの技 ----
        /** 技を出すときの高さ（地面から）と、標的からの水平の距離 */
        const val HOVER_HEIGHT = "hover-height"
        const val HOVER_DISTANCE = "hover-distance"
        const val LOOP_ANIMATION = "loop-animation"
        // ブレス
        const val BREATH_TICKS = "breath-ticks"
        const val SWEEP_DEGREES = "sweep-degrees"
        const val SWEEPS = "sweeps"
        const val CONE_DEGREES = "cone-degrees"
        // 火球（fireball セクション）と、燃える地面
        const val FIREBALL = "fireball"
        const val LEAD_RATIO = "lead-ratio"
        const val BURN_RADIUS = "burn-radius"
        const val BURN_TICKS = "burn-ticks"
        const val BURN_DAMAGE = "burn-damage"
        const val BURN_INTERVAL_TICKS = "burn-interval-ticks"
        const val BLOCK = "block"
        // 急降下
        const val CLIMB_HEIGHT = "climb-height"
        const val CLIMB_TICKS = "climb-ticks"
        const val CLIMB_ANIMATION = "climb-animation"
        const val DIVE_SPEED = "dive-speed"
        const val DIVE_ANIMATION = "dive-animation"
        const val DIVE_MAX_TICKS = "dive-max-ticks"
        const val BOTTOM_HEIGHT = "bottom-height"
        const val PULL_ANIMATION = "pull-animation"
        const val PULL_TICKS = "pull-ticks"
        const val RECOVER_ANIMATION = "recover-animation"
        const val RECOVER_TICKS = "recover-ticks"
        const val RECOVER_HEIGHT = "recover-height"
        const val RECOVER_DAMAGE_MULTIPLIER = "recover-damage-multiplier"
        const val REPEATS = "repeats"
        // 翼の突風
        const val GUST_TICKS = "gust-ticks"
        const val GUST_RADIUS = "gust-radius"
        // 業火の雨・撃ち落とし
        const val RAIN_HEIGHT = "rain-height"
        const val CAST_TICKS = "cast-ticks"
        const val FIREBALLS_PER_WAVE = "fireballs-per-wave"
        const val DESCEND_TICKS = "descend-ticks"
        const val EXHAUSTED_HEIGHT = "exhausted-height"
        const val FALL_TICKS = "fall-ticks"
        const val MESSAGE = "message"

        // ---- 近接技の踏み込み（BoneStrikeSkill） ----
        const val LUNGE_WINDOWS = "lunge-windows"
        /** 技の説明（チュートリアルアルファが初めて使うときに出す） */
        const val HINT = "hint"
        const val LUNGE_SPEED = "lunge-speed"
        const val LUNGE_STOP_DISTANCE = "lunge-stop-distance"
        /** 振りながら後ろへ下がる区間・速さ */
        const val RETREAT_WINDOWS = "retreat-windows"
        const val RETREAT_SPEED = "retreat-speed"
        /** 近接技: 前方の扇の中（足元）にも当てる半径・角度 */
        const val CLOSE_RADIUS = "close-radius"
        const val CLOSE_DEGREES = "close-degrees"
        // ---- 髑髏騎士: 打ち上げ・溜め刺し・怨霊・雷 ----
        /** 打ち上げ: 斬り上げが当たる扇の角度 */
        const val HIT_DEGREES = "hit-degrees"
        /** 打ち上げ: 当てた相手を空へ飛ばす tick（ジャストガードの直後でも飛ぶよう、当てた 2tick 後） */
        const val LAUNCH_TICK = "launch-tick"
        /** 打ち上げ: 空へ飛ばす速さ（2.0 でおよそ 20 ブロック） */
        const val LAUNCH_VELOCITY = "launch-velocity"
        /** 打ち上げ: 騎士が追って跳ぶ tick・追いつくまでの tick */
        const val JUMP_TICK = "jump-tick"
        const val RISE_TICKS = "rise-ticks"
        /** 空中連撃: アニメ・太刀の数・間隔・1太刀のダメージ */
        const val COMBO_ANIMATION = "combo-animation"
        const val COMBO_HITS = "combo-hits"
        const val COMBO_INTERVAL_TICKS = "combo-interval-ticks"
        const val COMBO_DAMAGE = "combo-damage"
        const val FINISH_TICK = "finish-tick"
        const val FINISH_DAMAGE = "finish-damage"
        const val SMASH_VELOCITY = "smash-velocity"
        const val LAND_TICK = "land-tick"
        /** 空中で相手を留める、騎士の前の距離 */
        const val HOLD_DISTANCE = "hold-distance"
        /** 落下ダメージから守る長さ（status.fall） */
        const val FALL_PROTECT_TICKS = "fall-protect-ticks"
        /** 溜め刺し: 突く tick */
        const val THRUST_TICK = "thrust-tick"
        /** 溜め刺し: 振り回して投げるアニメ・その長さ */
        const val SWING_ANIMATION = "swing-animation"
        const val SWING_START_TICK = "swing-start-tick"
        const val SWING_TICKS = "swing-ticks"
        /** 溜め刺し: 投げる速さ（前・上）・投げたときのダメージ */
        const val THROW_SPEED = "throw-speed"
        const val THROW_UP = "throw-up"
        const val THROW_DAMAGE = "throw-damage"
        /** 溜め刺し: 刺している間のダメージと間隔 */
        const val HOLD_DAMAGE = "hold-damage"
        const val HOLD_DAMAGE_INTERVAL = "hold-damage-interval"
        /** 溜め刺し: 刺した相手を留める刃の位置（柄 0 〜 切っ先 1） */
        const val BLADE_POINT = "blade-point"
        /** 怨霊: 1tick に向きを変えられる角度・飛び出す速さ */
        const val TURN_DEGREES = "turn-degrees"
        const val LAUNCH_SPEED = "launch-speed"
        /** 雷: 呼び続けるアニメ・始める tick・呼び続ける長さ */
        const val CHANNEL_ANIMATION = "channel-animation"
        const val CHANNEL_START_TICK = "channel-start-tick"
        const val CHANNEL_TICKS = "channel-ticks"
        // ---- 髑髏騎士: 技の合間に相手の周りを回る（circle） ----
        /** 回り続ける時間（この間でランダム。tick） */
        const val MIN_TICKS = "min-ticks"
        const val MAX_TICKS = "max-ticks"
        /** 横ステップの間隔（この間でランダム。tick）・跳ぶ距離（ブロック）・向きを変える割合 */
        const val STEP_INTERVAL_MIN = "step-interval-min"
        const val STEP_INTERVAL_MAX = "step-interval-max"
        const val STEP_DISTANCE = "step-distance"
        const val STEP_REVERSE_CHANCE = "step-reverse-chance"
        /** 左へ・右へ回るときのアニメ（繰り返し）と、左へ・右への横ステップのアニメ */
        const val LEFT_ANIMATION = "left-animation"
        const val RIGHT_ANIMATION = "right-animation"
        const val STEP_LEFT_ANIMATION = "step-left-animation"
        const val STEP_RIGHT_ANIMATION = "step-right-animation"
        // ---- 髑髏騎士: 瀕死（吹き飛ばし → 怨霊の弾幕） ----
        /** 全員を吹き飛ばす tick */
        const val BLAST_TICK = "blast-tick"
        /** 吹き飛ばした相手が着く、ボスからのおよその距離 */
        const val PUSH_DISTANCE = "push-distance"
        /** 怨霊を放ち始める（＝近接の一撃で倒せるようになる）tick */
        const val BARRAGE_START_TICK = "barrage-start-tick"
        /** 怨霊が届くまでの最短の tick（近くても反応できるように） */
        const val MIN_FLIGHT_TICKS = "min-flight-ticks"

        // ---- 暗殺者 ----
        // 影渡り
        const val SINK_ANIMATION = "sink-animation"
        const val SINK_TICKS = "sink-ticks"
        const val HIDE_TICK = "hide-tick"
        const val TRAVEL_SPEED = "travel-speed"
        const val TRAVEL_MAX_TICKS = "travel-max-ticks"
        const val BEHIND_DISTANCE = "behind-distance"
        const val LOCK_DISTANCE = "lock-distance"
        const val EMERGE_ANIMATION = "emerge-animation"
        const val EMERGE_TICKS = "emerge-ticks"
        const val DECOYS = "decoys"
        const val DECOY_DAMAGE = "decoy-damage"
        const val DECOY_RADIUS = "decoy-radius"
        const val EXPOSED_ANIMATION = "exposed-animation"
        const val EXPOSED_TICKS = "exposed-ticks"
        const val EXPOSED_DAMAGE_MULTIPLIER = "exposed-damage-multiplier"
        const val EXPOSED_MESSAGE = "exposed-message"
        // 苦無
        const val RELEASE_TICKS = "release-ticks"
        const val FAN_DEGREES = "fan-degrees"
        // 影縫い
        const val BIND_RADIUS = "bind-radius"
        const val BIND_MESSAGE = "bind-message"
        const val FOLLOW_UP = "follow-up"
        // 影舞
        const val DASHES = "dashes"
        const val DASH_TICKS = "dash-ticks"
        const val DASH_ANIMATION = "dash-animation"
        const val FIRST_WARNING_TICKS = "first-warning-ticks"
        const val OVERSHOOT = "overshoot"
        const val LINE_WIDTH = "line-width"
        const val FINISH_ANIMATION = "finish-animation"
        const val FINISH_WARNING_TICKS = "finish-warning-ticks"
        const val FINISH_LAND_TICK = "finish-land-tick"
        const val FINISH_TICKS = "finish-ticks"
        // 分身（囲み・残像の連撃・分身の苦無）
        const val CLONES = "clones"
        const val RADIUS = "radius"
        const val APPEAR_ANIMATION = "appear-animation"
        const val APPEAR_TICKS = "appear-ticks"
        const val ORBIT_DEGREES = "orbit-degrees"
        const val ORBIT_INTERVAL_TICKS = "orbit-interval-ticks"
        const val STEP_TICKS = "step-ticks"
        const val FOUND_MESSAGE = "found-message"
        const val FAKE_MESSAGE = "fake-message"
        const val FAIL_MESSAGE = "fail-message"
        const val FLANK_DISTANCE = "flank-distance"
        const val CLONE_DELAY_TICKS = "clone-delay-ticks"
        const val CLONE_ANIMATION = "clone-animation"
        const val CLONE_TICKS = "clone-ticks"
        const val CLONE_HIT_WINDOWS = "clone-hit-windows"
        const val CLONE_HIT_RADIUS = "clone-hit-radius"
        const val CLONE_HIT_DEGREES = "clone-hit-degrees"
        const val CLONE_DAMAGE = "clone-damage"
        // 大巨人の動き（moves.<ID>）と連携
        const val MOVES = "moves"
        const val IMPACT_TICK = "impact-tick"
        const val WARN_START_TICK = "warn-start-tick"
        const val AIM_LOCK_TICK = "aim-lock-tick"
        const val FORWARD = "forward"
        const val WIDTH = "width"
        const val SHAKE_RADIUS = "shake-radius"
        const val TAKEOFF_TICK = "takeoff-tick"
        const val MAX_DISTANCE = "max-distance"
        const val CHAIN_GAP_TICKS = "chain-gap-ticks"
        /** 大巨人: 連携の長さをランダムにするとき、moves の後に足す動きの候補と、連携の長さ（動きの数）の範囲 */
        const val CHAIN_POOL = "chain-pool"
        const val CHAIN_MIN = "chain-min"
        const val CHAIN_MAX = "chain-max"
        const val RECOVER_END_ANIMATION = "recover-end-animation"
        const val RECOVER_END_TICKS = "recover-end-ticks"
        // ---- 髑髏騎士 ----
        /** 連撃: 順に出す技の ID（skills.<ID>） */
        const val STEPS = "steps"
        /** 地割れ: 振り始めから何 tick 目に走り出すか・長さ・幅・1ブロック進むのにかかる tick・本数と広がり */
        const val CRACK_TICK = "crack-tick"
        const val CRACK_LENGTH = "crack-length"
        const val CRACK_WIDTH = "crack-width"
        const val CRACK_DAMAGE = "crack-damage"
        const val CRACK_STEP_TICKS = "crack-step-ticks"
        const val CRACK_COUNT = "crack-count"
        const val CRACK_SPREAD_DEGREES = "crack-spread-degrees"
        /** 斬撃波: 振り始めから何 tick 目に放つか・速さ・届く距離・幅・ダメージ */
        const val CRESCENT_TICK = "crescent-tick"
        const val CRESCENT_SPEED = "crescent-speed"
        const val CRESCENT_RANGE = "crescent-range"
        const val CRESCENT_WIDTH = "crescent-width"
        const val CRESCENT_DAMAGE = "crescent-damage"
        /**
         * 浮遊: 宙に浮いてから打つ割合（0〜1）・高さ・浮き上がる tick・宙に留まる最短の tick・浮いて寄る最長の tick・
         * 寄る速さ・標的からこの距離まで寄る・降りる tick（技の始めから。書かなければ最初の判定の 1tick 前）・
         * 浮いている間のアニメ・宙から打つときの技のアニメ
         */
        const val FLOAT_CHANCE = "float-chance"
        const val FLOAT_HEIGHT = "float-height"
        const val FLOAT_RISE_TICKS = "float-rise-ticks"
        const val FLOAT_HOVER_TICKS = "float-hover-ticks"
        const val FLOAT_MAX_TICKS = "float-max-ticks"
        const val FLOAT_GLIDE_SPEED = "float-glide-speed"
        const val FLOAT_DISTANCE = "float-distance"
        const val FLOAT_DESCEND_TICKS = "float-descend-ticks"
        const val FLOAT_ANIMATION = "float-animation"
        /** 浮き上がる間に1回流すアニメ・宙で標的へ寄っている間に繰り返すアニメ */
        const val FLOAT_RISE_ANIMATION = "float-rise-animation"
        const val FLOAT_GLIDE_ANIMATION = "float-glide-animation"
        const val AIR_ANIMATION = "air-animation"
        /** 浮き上がりながら斜め後ろへ下がる距離・真後ろから斜めにする角度 */
        const val FLOAT_BACK_DISTANCE = "float-back-distance"
        const val FLOAT_BACK_DEGREES = "float-back-degrees"
        /** 連携の最後に出す技（疲れなど。演出の技の ID） */
        const val FINISHER = "finisher"
        /** 溜め: 大剣に光が集まる区間（tick。振り始めから） */
        const val CHARGE_WINDOWS = "charge-windows"
    }

    /** messages セクション内のキー */
    object Message {
        const val RELOAD = "reload"
        const val RELOAD_FAILED = "reload-failed"
        const val PLAYER_ONLY = "player-only"
        const val LOCATION_SET = "location-set"
        const val LOCATION_NOT_SET = "location-not-set"
        const val LOCATION_WORLD_MISSING = "location-world-missing"
        const val LOCATION_INFO = "location-info"
        const val LOCATION_TELEPORT = "location-teleport"
        const val TRADER_SPAWNED = "trader-spawned"
        const val TRADER_REMOVED = "trader-removed"
        const val TRADER_PUNCHED = "trader-punched"
        const val BOSS_SUMMONED = "boss-summoned"
        const val BOSS_UNKNOWN = "boss-unknown"
        const val BOSS_MODEL_MISSING = "boss-model-missing"
        const val BOSS_LIST = "boss-list"
        const val BOSS_REMOVED = "boss-removed"
        const val DEBUG_ON = "debug-on"
        const val DEBUG_OFF = "debug-off"
        const val MENU_BOSS_HEALTH = "menu-boss-health"
        const val MENU_BOSS_CLICK = "menu-boss-click"
        const val MENU_BOSS_RECORD = "menu-boss-record"
        const val MENU_BOSS_RECORD_NONE = "menu-boss-record-none"
        const val MENU_BOSS_PERSONAL = "menu-boss-personal"
        const val MENU_BOSS_PERSONAL_NONE = "menu-boss-personal-none"
        const val BATTLE_BUSY = "battle-busy"
        const val BATTLE_RECRUIT = "battle-recruit"
        const val BATTLE_JOIN_BUTTON = "battle-join-button"
        const val BATTLE_JOINED = "battle-joined"
        const val BATTLE_ALREADY_JOINED = "battle-already-joined"
        const val BATTLE_JOIN_CLOSED = "battle-join-closed"
        const val BATTLE_COUNTDOWN_DISPLAY = "battle-countdown-display"
        const val BATTLE_COUNTDOWN_TITLE = "battle-countdown-title"
        const val BATTLE_COUNTDOWN_SUBTITLE = "battle-countdown-subtitle"
        const val BATTLE_START_TITLE = "battle-start-title"
        const val BATTLE_START_SUBTITLE = "battle-start-subtitle"
        const val BATTLE_START = "battle-start"
        const val BATTLE_DEATH = "battle-death"
        const val BATTLE_ELIMINATED = "battle-eliminated"
        const val BATTLE_ELIMINATED_TITLE = "battle-eliminated-title"
        const val BATTLE_ELIMINATED_SUBTITLE = "battle-eliminated-subtitle"
        const val BATTLE_WIPED_TITLE = "battle-wiped-title"
        const val BATTLE_WIPED_SUBTITLE = "battle-wiped-subtitle"
        const val BATTLE_PLAYER_CANCELLED = "battle-player-cancelled"
        const val MENU_CANCEL_NAME = "menu-cancel-name"
        const val MENU_CANCEL_BOSS = "menu-cancel-boss"
        const val MENU_CANCEL_CLICK = "menu-cancel-click"
        const val MENU_CANCEL_CONFIRM = "menu-cancel-confirm"
        const val MENU_CANCEL_DENIED = "menu-cancel-denied"
        const val RECORD_RESET_ALL = "record-reset-all"
        const val RECORD_RESET_PLAYER = "record-reset-player"
        const val RECORD_RESET_PLAYER_ALL = "record-reset-player-all"
        const val BATTLE_DISMOUNTED = "battle-dismounted"
        const val SHIELD_BROKEN = "shield-broken"
        const val JUST_GUARD = "just-guard"
        const val JUST_GUARD_REFLECT = "just-guard-reflect"
        const val BATTLE_FAILED = "battle-failed"
        const val BATTLE_VICTORY = "battle-victory"
        const val BATTLE_DAMAGE_HEADER = "battle-damage-header"
        const val BATTLE_DAMAGE_LINE = "battle-damage-line"
        const val BATTLE_ENDED = "battle-ended"
        const val BATTLE_CLEAR_TIME = "battle-clear-time"
        const val BATTLE_BOSSBAR_TIMER = "battle-bossbar-timer"
        const val BATTLE_ABANDONED = "battle-abandoned"
        const val BATTLE_CANCELLED = "battle-cancelled"
        const val BATTLE_CANCEL_DONE = "battle-cancel-done"
        const val BATTLE_NONE = "battle-none"
        const val RECORD_RESET = "record-reset"
        const val RECORD_GROUP_RANKED = "record-group-ranked"
        const val MENU_BOSS_GROUP_RECORD = "menu-boss-group-record"
        const val MENU_BOSS_GROUP_RECORD_NONE = "menu-boss-group-record-none"
        const val RANKING_GROUP_HEADER = "ranking-group-header"
        const val RANKING_GROUP_ENTRY = "ranking-group-entry"
        const val RECORD_RANKED = "record-ranked"
        const val RECORD_FIRST_CLEAR = "record-first-clear"
        const val RECORD_NEW_BEST = "record-new-best"
        const val RANKING_HEADER = "ranking-header"
        const val RANKING_ENTRY = "ranking-entry"
        const val RANKING_EMPTY = "ranking-empty"
        const val RANKING_PERSONAL = "ranking-personal"
        const val RANKING_PERSONAL_NONE = "ranking-personal-none"
        // ボスの解放
        const val MENU_BOSS_LOCKED = "menu-boss-locked"
        const val BATTLE_LOCKED = "battle-locked"
        const val PROGRESS_UNLOCKED = "progress-unlocked"
        const val PROGRESS_COMPLETE = "progress-complete"
        const val PROGRESS_INFO = "progress-info"
        const val PROGRESS_GRANTED = "progress-granted"
        const val PROGRESS_REVOKED = "progress-revoked"
        const val PLAYER_UNKNOWN = "player-unknown"
        // /eb config
        const val CONFIG_VALUE = "config-value"
        const val CONFIG_SET = "config-set"
        const val CONFIG_UNKNOWN_FILE = "config-unknown-file"
        const val CONFIG_UNKNOWN_KEY = "config-unknown-key"
        const val CONFIG_SECTION = "config-section"
        const val CONFIG_INVALID = "config-invalid"
    }

    /** エンティティなどに付ける PersistentDataContainer のキー */
    object Key {
        /** 旅商人 NPC の目印 */
        val TRADER = NamespacedKey(NAMESPACE, "trader")

        /** ボス本体の目印（値はボスID） */
        val BOSS = NamespacedKey(NAMESPACE, "boss")

        /** ボスの手下の目印（値は持ち主のボスの UUID） */
        val MINION = NamespacedKey(NAMESPACE, "minion")

        /** ボスの分身（おとり）の当たり判定の目印（値は持ち主のボスの UUID） */
        val DECOY = NamespacedKey(NAMESPACE, "decoy")

        /** ボス本体の「追うだけ（殴らない）」AI のキー（boss/ChaseGoal） */
        val CHASE_GOAL = NamespacedKey(NAMESPACE, "chase")
    }
}
