import os
import torch
from PIL import Image, UnidentifiedImageError
from torchvision import transforms
from transformers import CLIPProcessor, CLIPModel
import matplotlib.pyplot as plt
import time

def get_top_image_matches(text_query, image_folder="/Users/henneberger/federate/images", cache_folder="cache", top_n=10, hours_ago=None):
    # Ensure the cache folder exists
    os.makedirs(cache_folder, exist_ok=True)

    # Load the CLIP model and processor
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32").to(device)
    processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")

    # Preprocessing pipeline for images
    preprocess = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
    ])

    # Load images from the folder
    image_files = [f for f in os.listdir(image_folder) if f.lower().endswith(('.png', '.jpg', '.jpeg', '.gif'))]
    if not image_files:
        print("No images found in the folder.")
        return []

    # Process images and compute/load embeddings
    image_embeddings = []
    valid_image_files = []  # Track valid images for embedding
    current_time = time.time()
    for i, image_file in enumerate(image_files):

        image_path = os.path.join(image_folder, image_file)
        creation_time = os.path.getctime(image_path)

        if current_time - creation_time > hours_ago * 3600:
            continue
        print(i)
        print(creation_time)
        print(current_time)
        print(current_time - creation_time)
        print( hours_ago * 3600)
        cache_path = os.path.join(cache_folder, f"{os.path.splitext(image_file)[0]}.pt")
        try:
            if os.path.exists(cache_path):
                # Load cached embedding
                image_embedding = torch.load(cache_path).to(device)
            else:
                # Compute embedding and cache it
                image = Image.open(image_path).convert("RGB")
                image_tensor = preprocess(image).unsqueeze(0).to(device)
                with torch.no_grad():
                    image_embedding = model.get_image_features(image_tensor)
                torch.save(image_embedding.cpu(), cache_path)
            image_embeddings.append(image_embedding)
            valid_image_files.append(image_file)
        except (UnidentifiedImageError, IOError) as e:
            print(f"Error reading {image_file}: {e}")

    # If no valid images, return early
    if not valid_image_files:
        print("No valid images found.")
        return []

    # Compute text embedding
    text_inputs = processor(text=text_query, return_tensors="pt", padding=True).to(device)
    with torch.no_grad():
        text_embedding = model.get_text_features(**text_inputs)

    # Calculate cosine similarity
    similarities = []
    for i, image_embedding in enumerate(image_embeddings):
        similarity = torch.nn.functional.cosine_similarity(image_embedding, text_embedding)
        similarities.append((valid_image_files[i], similarity.item()))

    # Sort by similarity and return top N matches
    top_matches = sorted(similarities, key=lambda x: x[1], reverse=True)[:top_n]
    print(top_matches)
    return top_matches

def display_images_with_matplotlib(image_folder, top_matches):
    # Helper function to load images
    def load_image(image_path):
        return Image.open(image_path)

    # Helper function to update the displayed image
    def update_image(delta):
        nonlocal image_index
        image_index = (image_index + delta) % len(top_matches)
        ax.clear()
        img_path = os.path.join(image_folder, top_matches[image_index][0])
        image = load_image(img_path)
        ax.imshow(image)
        ax.axis("off")
        ax.set_title(f"Image {image_index + 1}/{len(top_matches)}: {top_matches[image_index][0]}")
        fig.canvas.draw()

    # Initialize matplotlib
    image_index = 0
    fig, ax = plt.subplots(figsize=(8, 8))
    update_image(0)

    # Key event handling
    def on_key(event):
        if event.key == "right":
            update_image(1)
        elif event.key == "left":
            update_image(-1)

    fig.canvas.mpl_connect("key_press_event", on_key)
    plt.show()

# Example usage
if __name__ == "__main__":
    import sys

    if len(sys.argv) < 3:
        print("Usage: python match_images.py '<text_query>' <hours_ago>")
    else:
        query = sys.argv[1]
        hours = float(sys.argv[2])
        matches = get_top_image_matches(query, top_n=200, hours_ago=hours)
        if matches:
            display_images_with_matplotlib("/Users/henneberger/federate/images", matches)