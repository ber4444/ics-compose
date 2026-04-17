import asyncio
import http.server
import socketserver
import threading
import os
import sys
from playwright.async_api import async_playwright
PORT = 8085
DIRECTORY = "composeApp/build/dist/wasmJs/productionExecutable"
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)
    def log_message(self, format, *args):
        pass
def start_server():
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        httpd.serve_forever()
async def run():
    if not os.path.exists(DIRECTORY):
        print(f"Directory {DIRECTORY} not found. Please run UI build first.")
        sys.exit(1)
    server_thread = threading.Thread(target=start_server, daemon=True)
    server_thread.start()
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page()
        print("Navigating to WASM App...")
        await page.goto(f"http://localhost:{PORT}")
        try:
            # Compose multiplatform uses a raw <canvas> element to render. 
            await page.wait_for_selector('canvas', timeout=15000)
            print("Canvas found!")
            # Additional wait to ensure it had time to load font/images.
            await page.wait_for_timeout(3000)
        except Exception as e:
            print("Canvas not found or timeout:", e)
        screenshot_path = "e2e/wasm_screenshot.png"
        await page.screenshot(path=screenshot_path)
        print(f"Screenshot saved to {screenshot_path}")
        file_size = os.path.getsize(screenshot_path)
        # When compose fails, it may show a small blank screen (<10kb).
        if file_size < 10000:
            print("Error: The screen appears to be empty! Screenshot size is too small.")
            sys.exit(1)
        print("Test passed! WASM build renders a functional screen that is not empty.")
        await browser.close()
if __name__ == "__main__":
    asyncio.run(run())
