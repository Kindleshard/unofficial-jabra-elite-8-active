"""Voice Assistant choice: switch Sound+ to 'Android default', diff the earbuds, switch back to Google."""
import sys
import pathlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import decode_features as d  # noqa: E402

buds = d.g.Earbuds(d.g.DEFAULT_ADDR, d.g.DEFAULT_CHANNEL)
try:
    d.open_settings("Voice Assistant")

    def pick(label):
        def go():
            d.tap_text(label, exact=False)
            print("   screen after tap:", [n["text"] for n in d.nodes() if n["text"]][:8], flush=True)
        return go

    d.experiment(buds, "Voice assistant: Android default", pick("Android"), pick("Google"))
finally:
    buds.close()
