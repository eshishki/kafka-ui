import React from 'react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, WithRoute } from 'lib/testHelpers';
import { clusterSchemaEditPath } from 'lib/paths';
import { SchemaType } from 'generated-sources';
import Form from 'components/Schemas/Edit/Form';
import {
  useCreateSchema,
  useUpdateSchemaCompatibilityLayer,
} from 'lib/hooks/api/schemas';

jest.mock('lib/hooks/api/schemas', () => ({
  useCreateSchema: jest.fn(),
  useUpdateSchemaCompatibilityLayer: jest.fn(),
}));

jest.mock('components/common/Editor/Editor', () => () => <div />);

const update = jest.fn();
const create = jest.fn();

const renderForm = (compatibilityLevel: string) =>
  render(
    <WithRoute path={clusterSchemaEditPath()}>
      <Form
        schema={{
          subject: 'orders-value',
          version: '1',
          id: 1,
          schemaType: SchemaType.AVRO,
          schema: '{"type":"record","name":"Order","fields":[]}',
          compatibilityLevel,
        }}
      />
    </WithRoute>,
    {
      initialEntries: [clusterSchemaEditPath('local', 'orders-value')],
    }
  );

describe('Schema compatibility form', () => {
  beforeEach(() => {
    update.mockReset();
    create.mockReset();
    (useCreateSchema as jest.Mock).mockReturnValue({ mutateAsync: create });
    (useUpdateSchemaCompatibilityLayer as jest.Mock).mockReturnValue({
      mutateAsync: update,
    });
  });

  it('displays an existing ICEBERG subject without making it dirty', () => {
    renderForm('ICEBERG');
    expect(screen.getByText('ICEBERG')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit' })).toBeDisabled();
    expect(update).not.toHaveBeenCalled();
  });

  it('submits ICEBERG for a subject without registering a new schema', async () => {
    renderForm('BACKWARD');
    const dropdown = screen.getAllByRole('listbox')[1];
    await userEvent.click(within(dropdown).getByRole('option'));
    await userEvent.click(within(dropdown).getByText('ICEBERG'));
    await userEvent.click(screen.getByRole('button', { name: 'Submit' }));
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(
        expect.objectContaining({
          subject: 'orders-value',
          compatibilityLevel: { compatibility: 'ICEBERG' },
        })
      )
    );
    expect(create).not.toHaveBeenCalled();
  });
});
