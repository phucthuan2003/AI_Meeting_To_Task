import os
from threading import Event, Thread


class GpuMemory:
    """Sample process VRAM, including allocations made by CTranslate2."""

    def __init__(self, enabled: bool):
        self.enabled = enabled
        self.peak_bytes = None
        self.devices = set()
        self._stop = Event()
        self._thread = None

    def __enter__(self):
        if self.enabled:
            import pynvml

            self.nvml = pynvml
            try:
                pynvml.nvmlInit()
            except pynvml.NVMLError:
                return self
            self._thread = Thread(target=self._run, daemon=True)
            self._thread.start()
        return self

    def __exit__(self, *_):
        if self._thread is not None:
            self._stop.set()
            self._thread.join()
            self.nvml.nvmlShutdown()

    def _run(self):
        nvml = self.nvml
        try:
            handles = [nvml.nvmlDeviceGetHandleByIndex(i) for i in range(nvml.nvmlDeviceGetCount())]
        except nvml.NVMLError:
            return
        while not self._stop.is_set():
            total = 0
            for handle in handles:
                try:
                    for process in nvml.nvmlDeviceGetComputeRunningProcesses(handle):
                        memory = process.usedGpuMemory
                        if process.pid == os.getpid() and memory is not None and 0 < memory < 2**63:
                            total += memory
                            name = nvml.nvmlDeviceGetName(handle)
                            self.devices.add(name.decode() if isinstance(name, bytes) else name)
                except nvml.NVMLError:
                    continue
            if total:
                self.peak_bytes = max(self.peak_bytes or 0, total)
            self._stop.wait(0.02)
