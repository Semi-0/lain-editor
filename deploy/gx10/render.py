#!/usr/bin/env python3
"""Render private configuration without logging inputs or credentials."""
import ipaddress
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit


def render(inputs, destination):
    root = Path(__file__).resolve().parents[2]
    destination = destination.resolve()
    if destination == root or root in destination.parents:
        raise ValueError('Generated configuration must be outside the checkout')
    address = ipaddress.ip_address(inputs['gx10_address'])
    subnet = ipaddress.ip_network(inputs['client_subnet'])
    if not address.is_private or address.is_loopback or address.is_unspecified:
        raise ValueError('GX10 address must be a private LAN address')
    if subnet.prefixlen == 0 or not subnet.network_address.is_private or address.version != subnet.version:
        raise ValueError('Client subnet must be an explicit private network of the same IP version')
    smtp = inputs['smtp']
    relay = urlsplit(smtp['address'])
    if relay.scheme not in ('smtp', 'submission', 'submissions') or not relay.hostname or relay.username or relay.password:
        raise ValueError('SMTP requires a relay URL without credentials')
    for key in ('sender', 'username', 'startup_check_address'):
        if not isinstance(smtp[key], str) or not smtp[key].strip():
            raise ValueError('SMTP settings must be explicitly supplied')
    operators = inputs['operators']
    if not isinstance(operators, list) or not operators:
        raise ValueError('At least one operator is required')
    users = {}
    for operator in operators:
        name = operator['username']
        if not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', name) or name in users:
            raise ValueError('Operator names must be unique safe identifiers')
        if not operator['password_hash'].startswith('$argon2id$'):
            raise ValueError('Operator requires a privately generated Argon2id password hash')
        if not operator['email'] or not operator['displayname']:
            raise ValueError('Operator email and display name are required')
        users[name] = {key: operator[key] for key in ('displayname', 'email')}
        users[name]['password'] = operator['password_hash']
        users[name]['groups'] = ['lain-operators']
    gateway = Path(inputs['gateway_credential_file']).read_text().strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]{32,}', gateway):
        raise ValueError('Invalid gateway credential file')
    substitutions = {
        '__GX10_ADDRESS__': str(address), '__CLIENT_SUBNET__': str(subnet),
        '__SMTP_ADDRESS__': json.dumps(smtp['address']),
        '__SMTP_SENDER__': json.dumps(smtp['sender']),
        '__SMTP_USERNAME__': json.dumps(smtp['username']),
        '__SMTP_CHECK_ADDRESS__': json.dumps(smtp['startup_check_address']),
    }
    outputs = {'users.yml': json.dumps({'users': users}, indent=2) + '\n',
               'gateway-header.caddy': f'header_up X-Lain-Gateway "{gateway}"\n'}
    for source, target in [('Caddyfile.in', 'Caddyfile'), ('authelia.yml.in', 'configuration.yml')]:
        content = Path(__file__).with_name(source).read_text()
        for key, value in substitutions.items():
            content = content.replace(key, value)
        if re.search(r'__[A-Z_]+__', content):
            raise ValueError('Unresolved deployment input')
        outputs[target] = content
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    if destination.stat().st_mode & 0o077:
        raise ValueError('Output directory must have mode 0700')
    for name, content in outputs.items():
        # Refuse overwrite/symlinks; rotation renders a new directory.
        with open(destination / name, 'x', opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            output.write(content)


if __name__ == '__main__':
    try:
        if len(sys.argv) != 3:
            raise ValueError('Usage: render.py PRIVATE_INPUTS.json NEW_PRIVATE_OUTPUT_DIRECTORY')
        render(json.loads(Path(sys.argv[1]).read_text()), Path(sys.argv[2]))
    except (KeyError, ValueError, OSError, TypeError):
        sys.exit('Deployment configuration rejected: check required inputs, private file permissions, and unused output path')
