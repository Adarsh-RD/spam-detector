import json
import logging
import numpy as np
from pathlib import Path
from typing import List

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse
from pydantic import BaseModel

from model_builder import ModelBuilder

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Spam FL Server", version="1.0.0")

# ─── Paths ────────────────────────────────────────────────────────────────────
MODEL_DIR     = Path("models")
MODEL_PATH    = MODEL_DIR / "global_model.tflite"
VERSION_FILE  = MODEL_DIR / "version.txt"
UPDATES_FILE  = MODEL_DIR / "pending_updates.json"
MODEL_DIR.mkdir(exist_ok=True)

model_builder = ModelBuilder()


# ─── Helpers ──────────────────────────────────────────────────────────────────
def get_version() -> int:
    return int(VERSION_FILE.read_text().strip()) if VERSION_FILE.exists() else 0

def set_version(v: int):
    VERSION_FILE.write_text(str(v))

def load_pending() -> list:
    return json.loads(UPDATES_FILE.read_text()) if UPDATES_FILE.exists() else []

def save_pending(updates: list):
    UPDATES_FILE.write_text(json.dumps(updates))

def append_update(features: list, labels: list):
    updates = load_pending()
    updates.append({"features": features, "labels": labels})
    save_pending(updates)


# ─── Request Schema ───────────────────────────────────────────────────────────
class UpdatePayload(BaseModel):
    device_id: str
    features: List[List[int]]   # each inner list is int[100] tokenized message
    labels: List[int]           # 0 = ham, 1 = spam
    model_version: int          # client's current model version


# ─── Endpoints ────────────────────────────────────────────────────────────────
@app.get("/")
def root():
    return {"service": "Spam FL Server", "status": "running", "version": get_version()}


@app.get("/status")
def status():
    updates = load_pending()
    total_samples = sum(len(u["labels"]) for u in updates)
    return {
        "model_version": get_version(),
        "pending_updates": len(updates),
        "total_samples": total_samples,
        "model_available": MODEL_PATH.exists(),
    }


@app.post("/upload")
async def upload_update(payload: UpdatePayload, background_tasks: BackgroundTasks):
    if len(payload.features) != len(payload.labels):
        raise HTTPException(400, "features and labels length mismatch")
    if not payload.features:
        raise HTTPException(400, "No training samples provided")
    for feat in payload.features:
        if len(feat) != 100:
            raise HTTPException(400, f"Each feature must have length 100, got {len(feat)}")

    logger.info(f"Received {len(payload.features)} samples from device {payload.device_id[:8]}…")
    append_update(payload.features, payload.labels)

    # Trigger FedAvg after every upload (as requested)
    background_tasks.add_task(run_fedavg)

    return {
        "status": "accepted",
        "samples_received": len(payload.features),
        "message": "Training scheduled in background",
    }


@app.get("/get_model")
async def get_model():
    if not MODEL_PATH.exists():
        raise HTTPException(404, "No model yet — upload training data first.")
    return FileResponse(
        path=str(MODEL_PATH),
        media_type="application/octet-stream",
        filename="global_model.tflite",
        headers={"X-Model-Version": str(get_version())},
    )


@app.delete("/reset_model")
async def reset_model():
    """Admin endpoint: wipes stale model files so next upload rebuilds with correct architecture."""
    deleted = []
    for f in [MODEL_PATH, BASE_MODEL_PATH, VERSION_FILE, UPDATES_FILE]:
        if f.exists():
            f.unlink()
            deleted.append(str(f))
    logger.info(f"Model reset: deleted {deleted}")
    return {"status": "reset", "deleted": deleted}


# ─── FedAvg ───────────────────────────────────────────────────────────────────
def run_fedavg():
    """Aggregate all pending updates, fine-tune global model, export TFLite."""
    try:
        updates = load_pending()
        if not updates:
            logger.info("No pending updates — skipping.")
            return

        all_features, all_labels = [], []
        for u in updates:
            all_features.extend(u["features"])
            all_labels.extend(u["labels"])

        logger.info(f"FedAvg: aggregating {len(all_features)} samples from {len(updates)} upload(s)…")

        X = np.array(all_features, dtype=np.int32)
        y = np.array(all_labels,   dtype=np.int64)  # int64 for sparse_categorical_crossentropy

        # Delete stale base model if output shape is wrong (1-output vs expected 2-output)
        if BASE_MODEL_PATH.exists():
            try:
                import tensorflow as tf
                tmp = tf.keras.models.load_model(str(BASE_MODEL_PATH))
                if tmp.output_shape[-1] != 2:
                    logger.warning("Stale model has wrong output shape — deleting and rebuilding.")
                    BASE_MODEL_PATH.unlink()
            except Exception as check_err:
                logger.warning(f"Could not verify model shape, rebuilding: {check_err}")
                BASE_MODEL_PATH.unlink(missing_ok=True)

        model_builder.train(X, y, MODEL_PATH)

        new_version = get_version() + 1
        set_version(new_version)
        save_pending([])   # clear after successful aggregation

        logger.info(f"✅ Global model updated → version {new_version}")

    except Exception as e:
        logger.error(f"FedAvg error: {e}", exc_info=True)
