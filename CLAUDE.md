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
- HPに応じたフェーズ移行をサポートする
- onDisable で、全ボスのエンティティとモデルを必ず片付ける

## ボス一覧
（ボスを作るたびに、名前・モデル名・主なスキルを追記する）