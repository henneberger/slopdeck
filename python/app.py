import os
import torch
from PIL import Image, UnidentifiedImageError
from torchvision import transforms
from transformers import CLIPProcessor, CLIPModel
from flask import Flask, request, jsonify
from transformers import pipeline

MAX_TOKENS = 77

# Constants
# CACHE_FOLDER = "cache"
# os.makedirs(CACHE_FOLDER, exist_ok=True)

# Initialize Flask app
app = Flask(__name__)

# Model and processor initialization
device = "mps" if torch.backends.mps.is_available() else "cpu"
model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32").to(device)
processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
nsfw_model = pipeline("image-classification", model="AdamCodd/vit-base-nsfw-detector")

# Preprocessing pipeline for images
preprocess = transforms.Compose([
    transforms.Resize((128, 128)),  # Downscale to smaller resolution
    transforms.Pad((48, 48)),  # Pad to match 224x224
    transforms.ToTensor(),
    transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
])

def get_image_embedding(image_path):
    """
    Get the embedding for an image, using the cache if available.
    """
#     cache_path = os.path.join(CACHE_FOLDER, f"{os.path.splitext(os.path.basename(image_path))[0]}.pt")
#     if os.path.exists(cache_path):
#         Load cached embedding
#         return torch.load(cache_path).to(device)

    # Compute embedding
    try:
        image = Image.open(image_path).convert("RGB")
        image_tensor = preprocess(image).unsqueeze(0).to(device)
        with torch.no_grad():
            embedding = model.get_image_features(image_tensor)
#         torch.save(embedding.cpu(), cache_path)  # Cache the embedding
        return embedding
    except (UnidentifiedImageError, IOError) as e:
        raise ValueError(f"Error processing image {image_path}: {e}")

def classify_nsfw(image_path):
    """
    Classify the image as NSFW or SFW.
    """
    try:
        # Use the image path directly or convert to PIL.Image
        predictions = nsfw_model(image_path)  # Pass the path directly if supported by the model
        # Check if any label is classified as NSFW
        nsfw = any(pred['label'].lower() in ['nsfw', 'explicit'] and pred['score'] > 0.4 for pred in predictions)

        return nsfw
    except Exception as e:
        raise ValueError(f"Error classifying NSFW status: {e}")

@app.route("/embed_image_vector", methods=["POST"])
def embed_image():
    data = request.json
    image_path = data.get("image_path")

    if not image_path or not os.path.exists(image_path):
        return jsonify({"error": "Invalid or missing image_path"}), 400

    try:
        image_embedding = get_image_embedding(image_path)
        nsfw = False #classify_nsfw(image_path)

        return jsonify({
            "latent_vector": image_embedding.cpu().numpy().tolist(),
            "nsfw": nsfw
        })
    except ValueError as e:
        return jsonify({"error": str(e)}), 500
    except Exception as e:
        return jsonify({"error": f"Error processing request: {e}"}), 500

# Start the Flask server
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=6000)