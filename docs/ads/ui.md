# UI module (start screen, run commands, restarts)

Owner: `ui`. Code: `src/p3sim/clear/ui/`, tests: `test/p3sim/ui/`.

The module is the player's way to start and steer a run: the start screen (`RunStartScreen`), the layout browser, the `/ads run ...` commands
(`RunArgs` reads their words), the run settings (`UiSettings`, all named "Run ...") and the restart/savepoint commands (`Restarts`).
It has no per-tick work. Starting is a client matter (`RunStarter` checks the data packs, then `RunLauncher.launch`).

## Defaults and what overrides them

`RunDefaults` is what the run settings hold and what the start screen opens with. `/ads run random|seed|layout` takes every option it is not
given from the same defaults, so a choice made on the screen is also what the command uses. `RunDefaults.config` turns them into the `RunConfig`.

## Party strategy

The start screen has a "Strategy" button, shown only when an installed plug-in offers a preset. It cycles `auto`, `neutral`, then the presets
(`StrategyChoice`, over `BotStrategies.available()`). The choice is kept in the hidden "Run Party Strategy" setting (the list is not known until
a plug-in loads, so it cannot be a fixed selector).

- `auto` (the default) is the first preset the plug-ins offer. With no plug-in it is the neutral strategy, which sends nobody to the blood room. `RunDefaults.config` resolves it to the real id once, so the run, the
  summary line ("party strategy <id>") and the saved config all say what will happen.
- `neutral` is `RunConfig.strategy = null`.
- `/ads run ... strategy auto|neutral|<id>` names it; an id nobody offers is refused with the list of choices (it used to fall back to neutral silently).
- A saved id that no plug-in offers any more counts as `auto`.

## Not built: bot classes

A `classes ...` option and a "Bot classes" setting need changes outside this module (the integration requests in the fix report): a
`RunConfig.botClasses` field, `BotRoster.build` taking the list, and a distinct bot id and name for a repeated class. A party may have two of a
class, so the option is wanted. It is not built here because a bot's id (`BotRoster.botUuid`) and the leap order
(`BotsModule.leapOrder`) are per class today, and a setting that the run ignores would only mislead.
