-- Wave 5e / RTM issue #15: per-player preferences (control scheme,
-- particle visibility, double-jump toggle, quick-cast hotbar bindings).
-- `settings` is JSONB sealed-by-key — read side parses into the
-- PlayerSettings record (ControlScheme / ParticleVisibility variants).
CREATE TABLE player_settings (
    player_id   UUID PRIMARY KEY,
    settings    JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
