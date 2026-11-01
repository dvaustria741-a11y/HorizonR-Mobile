//! attack_entity hook: fires when a player attacks an entity.
//! Used by AntiKnockback (velocity module) and logging.

use liquid_horizon_engine as lh;

let out = lh::on_attack_entity(entity, kind);

if out.cancel {
    *canceled = true;
    return;
}

let _ = (pos, game);
