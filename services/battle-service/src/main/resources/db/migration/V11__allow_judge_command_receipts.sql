alter table match_command_receipt
    drop constraint match_command_receipt_type_supported,
    add constraint match_command_receipt_type_supported check (
        command_type in (
            'READY', 'DISCONNECT', 'RECONNECT', 'SURRENDER',
            'RUN', 'SUBMIT',
            'ATTACK_LAUNCH', 'ATTACK_BLOCK', 'ATTACK_REFLECT'
        )
    );
