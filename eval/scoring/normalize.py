from whisper_normalizer.basic import BasicTextNormalizer
from whisper_normalizer.english import EnglishTextNormalizer

_basic_normalizer = BasicTextNormalizer()
_english_normalizer = EnglishTextNormalizer()

def normalize_text(text: str, english_rules: bool = True) -> str:
    """
    Normalizes text for ASR evaluation using standard rules (lowercase, 
    remove punctuation, convert numbers to words, etc).
    """
    if english_rules:
        return _english_normalizer(text)
    return _basic_normalizer(text)
