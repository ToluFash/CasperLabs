
from .cl_node.wait import (
    wait_for_approved_block_received_handler_state,
    wait_for_blocks_count_at_least
)


def test_propose(started_standalone_bootstrap_node):
    wait_for_approved_block_received_handler_state(started_standalone_bootstrap_node, started_standalone_bootstrap_node.timeout)
    started_standalone_bootstrap_node.deploy()
    started_standalone_bootstrap_node.propose()
    wait_for_blocks_count_at_least(started_standalone_bootstrap_node, 1, 1, started_standalone_bootstrap_node.timeout)
