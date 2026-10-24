import sys
sys.dont_write_bytecode = True

import copy
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('renderer', Path(__file__).with_name('render.py'))
renderer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(renderer)


class RenderTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='lain-render-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'gateway').write_text('g' * 48)
        self.inputs = {
            'gx10_address': '192.168.1.10', 'client_subnet': '192.168.1.0/24',
            'gateway_credential_file': str(self.root / 'gateway'),
            'smtp': {'address': 'submission://smtp.lain.home.arpa:587', 'sender': 'auth@lain.home.arpa',
                     'username': 'auth', 'startup_check_address': 'operator@lain.home.arpa'},
            'operators': [{'username': 'operator', 'displayname': 'Operator',
                           'email': 'operator@lain.home.arpa', 'password_hash': '$argon2id$fixture'}]}

    def test_render_private_configuration_and_explicit_group(self):
        output = self.root / 'output'
        renderer.render(self.inputs, output)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o700)
        for path in output.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertNotIn('__SMTP_', path.read_text())
        users = json.loads((output / 'users.yml').read_text())['users']
        self.assertEqual(users['operator']['groups'], ['lain-operators'])
        self.assertEqual(len(users), 1)
        with self.assertRaises(FileExistsError):
            renderer.render(self.inputs, output)

    def test_required_inputs_cannot_be_omitted(self):
        for key in self.inputs:
            inputs = copy.deepcopy(self.inputs)
            del inputs[key]
            with self.assertRaises(KeyError):
                renderer.render(inputs, self.root / key)
        with self.assertRaises(ValueError):
            renderer.render(json.loads(Path(__file__).with_name('inputs.example.json').read_text()), self.root / 'empty')

    def test_rejects_unsafe_network_and_unprovisioned_operators(self):
        for key, value in [('gx10_address', '0.0.0.0'), ('gx10_address', '8.8.8.8'),
                           ('client_subnet', '0.0.0.0/0'), ('operators', [])]:
            inputs = {**self.inputs, key: value}
            with self.assertRaises(ValueError):
                renderer.render(inputs, self.root / 'bad')

    def test_checkout_and_public_output_are_rejected(self):
        with self.assertRaises(ValueError):
            renderer.render(self.inputs, Path(__file__).parent / 'generated')
        output = self.root / 'public'
        output.mkdir(mode=0o755)
        with self.assertRaises(ValueError):
            renderer.render(self.inputs, output)


if __name__ == '__main__':
    unittest.main()
