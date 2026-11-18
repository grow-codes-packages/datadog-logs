"""Grow Codes SDK for Datadog Log Management.

>>> from grow_datadog_logs import DatadogLogsClient, DatadogLogsConfig
>>> client = DatadogLogsClient.from_env()  # doctest: +SKIP
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import AsyncDatadogLogsClient, DatadogLogsClient
from .config import DEFAULT_URL, DatadogLogsConfig

__all__ = [
    "__version__",
    "DatadogLogsClient",
    "AsyncDatadogLogsClient",
    "DatadogLogsConfig",
    "DEFAULT_URL",
]
__all__ += _core.__all__

from . import _logging  # noqa: E402
from ._logging import *  # noqa: E402, F403

__all__ += _logging.__all__
