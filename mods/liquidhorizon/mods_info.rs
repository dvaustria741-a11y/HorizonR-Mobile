use liquid_horizon_engine as lh;
use crate::game::native_hooks::NativeMod;
let _ = game;
let (id, title, description) = lh::mod_info();
mods.push(NativeMod {
    id: id.into(),
    title: title.into(),
    description: description.into(),
    keys: vec![
        ["gui".into(),       "LiquidHorizon GUI".into(),  "key.keyboard.right.shift".into()],
        ["toggle".into(),    "Toggle All".into(),          "key.keyboard.insert".into()],
        ["killaura".into(),  "KillAura".into(),            "key.keyboard.r".into()],
        ["aimassist".into(), "AimAssist".into(),           "key.keyboard.z".into()],
        ["esp".into(),       "ESP".into(),                 "key.keyboard.x".into()],
        ["speed".into(),     "Speed".into(),               "key.keyboard.v".into()],
        ["flight".into(),    "Flight".into(),              "key.keyboard.f".into()],
        ["scaffold".into(),  "Scaffold".into(),            "key.keyboard.g".into()],
        ["nofall".into(),    "NoFall".into(),              "key.keyboard.n".into()],
        ["fullbright".into(),"FullBright".into(),          "key.keyboard.b".into()],
        ["criticals".into(), "Criticals".into(),           "key.keyboard.c".into()],
        ["velocity".into(),  "Velocity".into(),            "key.keyboard.k".into()],
        ["reach".into(),     "Reach".into(),               "key.keyboard.h".into()],
        ["triggerbot".into(),"Triggerbot".into(),          "key.keyboard.t".into()],
        ["autoeat".into(),   "AutoEat".into(),             "key.keyboard.semicolon".into()],
    ],
});
