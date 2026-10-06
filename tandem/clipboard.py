import asyncio

import pyperclip


class ClipboardSync:
    """Polls the local clipboard and pushes changes to connected devices.

    Remote writes go through set() which updates `last` so the watcher
    does not echo them back.
    """

    def __init__(self, on_change, interval: float = 0.6):
        self.on_change = on_change
        self.interval = interval
        self.last: str = ""
        self._task: asyncio.Task | None = None
        try:
            self.last = pyperclip.paste() or ""
        except Exception:
            pass

    def set(self, text: str) -> None:
        self.last = text
        try:
            pyperclip.copy(text)
        except Exception:
            pass

    async def _watch(self) -> None:
        while True:
            await asyncio.sleep(self.interval)
            try:
                cur = pyperclip.paste() or ""
            except Exception:
                continue
            if cur != self.last:
                self.last = cur
                if cur:
                    await self.on_change(cur)

    def start(self) -> None:
        self._task = asyncio.create_task(self._watch())

    def stop(self) -> None:
        if self._task:
            self._task.cancel()
