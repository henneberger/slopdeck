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

# Start the Flask server
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=6001)