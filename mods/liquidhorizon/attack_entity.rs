//! attack_entity hook — AntiKnockback (Velocity module).

use liquid_horizon_engine as lh;

let out = lh::on_attack_entity(entity, kind);
let _ = pos;

if out.cancel {
    *canceled = true;
}
