import numpy as np
import logging
import tensorflow as tf
from pathlib import Path

logger = logging.getLogger(__name__)

VOCAB_SIZE  = 100001   # covers all token IDs from the Android vocab.json
EMBEDDING_DIM = 16
MAX_LENGTH  = 100

BASE_MODEL_PATH = Path("models/base_model.keras")


class ModelBuilder:
    """Owns the Keras model lifecycle: build → fine-tune → export TFLite."""

    def __init__(self, vocab_size=10000, max_len=100):
        # Limit TF to 1 thread to prevent Render Free Tier from CPU throttling and freezing
        tf.config.threading.set_intra_op_parallelism_threads(1)
        tf.config.threading.set_inter_op_parallelism_threads(1)
        
        self.vocab_size = vocab_size
        self.max_len = max_len

    def _build_fresh_model(self):
        import tensorflow as tf
        # Output: 2 classes [ham_score, spam_score] — matches Android SpamClassifier.isSpam()
        # which reads out[0][0] (ham) and out[0][1] (spam)
        model = tf.keras.Sequential([
            tf.keras.layers.Embedding(
                VOCAB_SIZE, EMBEDDING_DIM,
                input_length=MAX_LENGTH, mask_zero=True
            ),
            tf.keras.layers.GlobalAveragePooling1D(),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.3),
            tf.keras.layers.Dense(2, activation="softmax"),  # 2 outputs: [ham, spam]
        ])
        model.compile(
            optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
            loss="sparse_categorical_crossentropy",
            metrics=["accuracy"],
        )
        return model

    def _load_or_create(self):
        import tensorflow as tf
        if BASE_MODEL_PATH.exists():
            try:
                model = tf.keras.models.load_model(str(BASE_MODEL_PATH))
                if model.output_shape[-1] != 2:
                    logger.warning("Stale model has 1-output shape — deleting and rebuilding with 2-output softmax.")
                    BASE_MODEL_PATH.unlink()
                    return self._build_fresh_model()
                logger.info("Loading existing Keras model for fine-tuning...")
                return model
            except Exception as e:
                logger.warning(f"Failed to load existing model ({e}), rebuilding.")
                BASE_MODEL_PATH.unlink(missing_ok=True)
        logger.info("No existing model — building fresh architecture.")
        return self._build_fresh_model()

    def train(self, X: np.ndarray, y: np.ndarray, output_tflite_path: Path):
        """Fine-tune on federated samples, then export quantized TFLite."""
        import tensorflow as tf

        model = self._load_or_create()

        val_split = 0.1 if len(X) >= 10 else 0.0
        logger.info(f"Training on {len(X)} samples (val_split={val_split})...")
        model.fit(
            X, y,
            epochs=10,
            batch_size=min(32, len(X)),
            validation_split=val_split,
            verbose=1,
        )

        # Persist Keras weights for the next round of fine-tuning
        BASE_MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
        model.save(str(BASE_MODEL_PATH))
        logger.info("Keras model saved.")

        # Export quantized TFLite
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_bytes = converter.convert()

        output_tflite_path.parent.mkdir(parents=True, exist_ok=True)
        output_tflite_path.write_bytes(tflite_bytes)
        logger.info(f"TFLite exported → {output_tflite_path} ({len(tflite_bytes)/1024:.1f} KB)")
