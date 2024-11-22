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
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
])

@app.route("/embed_text", methods=["POST"])
def compute_text_similarity():
    data = request.json
    input_text = data.get("input_text")

    if not input_text:
        return jsonify({"error": "input_text is required"}), 400

    try:
        # Function to process text: handle long inputs by splitting and averaging embeddings
        def get_text_embedding(text):
            chunks = split_text_into_chunks(text, MAX_TOKENS)
            embeddings = []
            for chunk in chunks:
                # Process each chunk with truncation
                text_inputs = processor(text=chunk, return_tensors="pt", padding=True, truncation=True, max_length=MAX_TOKENS).to(device)
                with torch.no_grad():
                    embedding = model.get_text_features(**text_inputs)
                # Normalize each embedding
                embedding = embedding / embedding.norm(p=2, dim=1, keepdim=True)
                embeddings.append(embedding)
            # Stack and average the embeddings
            embeddings = torch.vstack(embeddings)
            averaged_embedding = embeddings.mean(dim=0, keepdim=True)
            return averaged_embedding

        # Get embeddings for both input_text and text_query
        embedding_input = get_text_embedding(input_text)

        return jsonify({
            "latent_vector": embedding_input.cpu().numpy().tolist()
        })
    except Exception as e:
        return jsonify({"error": f"Error processing request: {e}"}), 500

def split_text_into_chunks(text, max_tokens=MAX_TOKENS):
    """
    Splits the input text into chunks that do not exceed the max_tokens limit.
    This is a simplistic approach and can be enhanced using more sophisticated
    text segmentation methods.
    """
    words = text.split()
    chunks = []
    current_chunk = []
    current_length = 0

    for word in words:
        # Estimate tokens by assuming 1 word ≈ 1 token (not always accurate)
        if current_length + 1 > max_tokens:
            if current_chunk:
                chunks.append(' '.join(current_chunk))
                current_chunk = []
                current_length = 0
        current_chunk.append(word)
        current_length += 1

    if current_chunk:
        chunks.append(' '.join(current_chunk))

    return chunks


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
        nsfw = any(pred['label'].lower() in ['nsfw', 'explicit'] and pred['score'] > 0.5 for pred in predictions)

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
        nsfw = classify_nsfw(image_path)

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