package dev.velora.util;

/**
 * Standard easing curves. Every animation in the client (panels, HUD fades,
 * ESP pulses) can pick a curve instead of hard-coding a formula.
 */
public enum Easing {

    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    SINE_IN {
        @Override
        public float apply(float t) {
            return 1f - (float) Math.cos(t * MathUtil.HALF_PI);
        }
    },
    SINE_OUT {
        @Override
        public float apply(float t) {
            return (float) Math.sin(t * MathUtil.HALF_PI);
        }
    },
    SINE_IN_OUT {
        @Override
        public float apply(float t) {
            return -(float) Math.cos(Math.PI * t) * 0.5f + 0.5f;
        }
    },
    QUAD_IN {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    QUAD_OUT {
        @Override
        public float apply(float t) {
            return 1f - (1f - t) * (1f - t);
        }
    },
    QUAD_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 2f * t * t : 1f - MathUtil.sq(-2f * t + 2f) * 0.5f;
        }
    },
    CUBIC_IN {
        @Override
        public float apply(float t) {
            return t * t * t;
        }
    },
    CUBIC_OUT {
        @Override
        public float apply(float t) {
            float inv = 1f - t;
            return 1f - inv * inv * inv;
        }
    },
    CUBIC_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) * 0.5f;
        }
    },
    EXPO_OUT {
        @Override
        public float apply(float t) {
            return t >= 1f ? 1f : 1f - (float) Math.pow(2f, -10f * t);
        }
    },
    CIRC_OUT {
        @Override
        public float apply(float t) {
            return (float) Math.sqrt(1f - MathUtil.sq(t - 1f));
        }
    },
    BACK_OUT {
        @Override
        public float apply(float t) {
            float c = 1.70158f;
            float inv = t - 1f;
            return 1f + (c + 1f) * inv * inv * inv + c * inv * inv;
        }
    },
    ELASTIC_OUT {
        @Override
        public float apply(float t) {
            if (t <= 0f) {
                return 0f;
            }
            if (t >= 1f) {
                return 1f;
            }
            float c = MathUtil.TWO_PI / 3f;
            return (float) Math.pow(2f, -10f * t) * (float) Math.sin((t * 10f - 0.75f) * c) + 1f;
        }
    },
    BOUNCE_OUT {
        @Override
        public float apply(float t) {
            float n = 7.5625f;
            float d = 2.75f;
            if (t < 1f / d) {
                return n * t * t;
            }
            if (t < 2f / d) {
                float v = t - 1.5f / d;
                return n * v * v + 0.75f;
            }
            if (t < 2.5f / d) {
                float v = t - 2.25f / d;
                return n * v * v + 0.9375f;
            }
            float v = t - 2.625f / d;
            return n * v * v + 0.984375f;
        }
    };

    /** @param t progress in {@code [0, 1]} @return eased progress */
    public abstract float apply(float t);

    /** Clamps {@code t} before easing. */
    public float ease(float t) {
        return apply(MathUtil.clamp01(t));
    }
}
