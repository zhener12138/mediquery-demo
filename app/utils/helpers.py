def not_empty(val) -> bool:
    """Return True if value is not None and not empty."""
    if val is None:
        return False
    if isinstance(val, str):
        return len(val.strip()) > 0
    if isinstance(val, (list, dict, set, tuple)):
        return len(val) > 0
    return True


def is_empty(val) -> bool:
    return not not_empty(val)
